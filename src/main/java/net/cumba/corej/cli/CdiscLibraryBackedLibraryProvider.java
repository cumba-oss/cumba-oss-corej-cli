package net.cumba.corej.cli;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.CustomLog;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.cdisc.library.api.model.products.Products;
import net.cumba.cdisc.library.api.model.sdtm.SdtmClass;
import net.cumba.cdisc.library.api.model.sdtm.SdtmDataset;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.cdisc.library.api.model.sdtm.SdtmVariable;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import net.cumba.web.api.Link;
import org.jspecify.annotations.Nullable;

/**
 * Production {@link LibraryProvider} binding over the CDISC Library API
 * ({@code net.cumba.cdisc.library.api}) for the Define-XML conformance engine's
 * {@code Requires: library} rules (plan define-library-provider).
 *
 * <p>
 * Unlike the deliberately unbound {@code CtProvider} (whose all-or-nothing skip gate would turn a
 * partial binding into false positives), the {@code LibraryProvider} SPI contract makes a partial
 * binding safe: every unknown standard/dataset/variable answers empty, which the library-backed
 * kinds treat as "out of the rule's reach" — a partial provider can only under-report, never
 * mis-fire. Every lookup failure (offline, HTTP error, unknown product) is therefore swallowed into
 * an empty answer, WARN-logged once per product.
 * </p>
 *
 * <p>
 * Coverage: SDTM-family products only ({@code SDTMIG}, {@code SENDIG} and its variants — the
 * define.xml 2.0 spellings {@code SDTM-IG}/{@code SEND-IG} are normalised per the SPI contract).
 * ADaM products answer empty (the shipped library rules are SDTM/SEND-scoped or conservative). The
 * offline pickle cache ({@code -pc}) is not consulted — this binding rides the Library API client
 * and its HTTP response cache.
 * </p>
 *
 * <p>
 * Data mapping: dataset labels and variables come from the IG product's
 * {@code classes() → datasets() → datasetVariables()} walk (plus model-shaped top-level
 * {@code datasets()}); a variable's CT codelist c-code is its {@code codelistLink} id; Core
 * designations come from {@code SdtmVariable.core()}; qualifier labels resolve through the IG's
 * {@code modelLink} to the SDTM Model's Events/Interventions class variables ({@code --OCCUR} →
 * fragment {@code OCCUR}); published versions come from the {@code /mdr/products} catalog hrefs.
 * </p>
 */
@CustomLog
public final class CdiscLibraryBackedLibraryProvider implements LibraryProvider
{

    /** Product / catalog fetch seam; package-visible so tests can inject canned products. */
    interface ProductSource
    {

        @Nullable
        SdtmProduct fetch(String aProductId, String aVersion) throws IOException;


        @Nullable
        Products catalog() throws IOException;
    }

    private final ProductSource source;

    /** {@code productId|version} → fetched IG (or Model) product; empty = unknown/unreachable. */
    private final Map<String, Optional<SdtmProduct>> products = new ConcurrentHashMap<>();

    /** Products already WARN-logged as unreachable, so a broken run logs once per product. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Single-key memo for the products catalog (same pattern as {@link #products}). */
    private final Map<String, Optional<Products>> catalogMemo = new ConcurrentHashMap<>();

    CdiscLibraryBackedLibraryProvider(ProductSource aSource)
    {
        source = aSource;
    }


    /** The production binding over a configured {@link CdiscLibraryClient}. */
    public static CdiscLibraryBackedLibraryProvider over(CdiscLibraryClient aClient)
    {
        return new CdiscLibraryBackedLibraryProvider(new ProductSource()
        {

            @Override
            public @Nullable SdtmProduct fetch(String aProductId, String aVersion)
                throws IOException
            {
                return aClient.getSdtmVersion(aProductId, aVersion, true);
            }


            @Override
            public @Nullable Products catalog() throws IOException
            {
                return aClient.getProducts();
            }
        });
    }


    @Override
    public Optional<String> datasetLabel(String aStandardName, String aStandardVersion,
            String aDatasetName)
    {
        return product(aStandardName, aStandardVersion)
                .flatMap(product -> dataset(product, aDatasetName)).flatMap(SdtmDataset::label);
    }


    @Override
    public Optional<String> variableLabel(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return variable(aStandardName, aStandardVersion, aDatasetName, aVariableName)
                .flatMap(SdtmVariable::label);
    }


    @Override
    public Optional<String> variableCodelistCCode(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return variable(aStandardName, aStandardVersion, aDatasetName, aVariableName)
                .flatMap(SdtmVariable::codelistLink).flatMap(Link::id);
    }


    @Override
    public Optional<String> variableCoreDesignation(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return variable(aStandardName, aStandardVersion, aDatasetName, aVariableName)
                .flatMap(SdtmVariable::core);
    }


    @Override
    public Optional<String> qualifierVariableLabel(String aStandardName, String aStandardVersion,
            String aFragment)
    {
        Optional<SdtmProduct> model = product(aStandardName, aStandardVersion)
                .flatMap(this::modelOf);
        if (model.isEmpty())
        {
            return Optional.empty();
        }
        String variableName = "--" + aFragment;
        for (SdtmClass klass : model.get().classes())
        {
            String className = klass.name().orElse("");
            if (!"Events".equals(className) && !"Interventions".equals(className))
            {
                continue;
            }
            for (SdtmVariable variable : klass.classVariables())
            {
                if (variable.name().filter(variableName::equals).isPresent())
                {
                    return variable.label();
                }
            }
        }
        return Optional.empty();
    }


    @Override
    public List<String> publishedStandardVersions(String aStandardName)
    {
        Optional<Products> products = catalog();
        if (products.isEmpty())
        {
            return List.of();
        }
        String family = productId(aStandardName);
        // Catalog hrefs are /mdr/<product>/<version>; the version segment's dashes are the
        // define.xml spelling's dots (3-1-2 <-> 3.1.2).
        return products.get().allLinks().stream().map(link -> link.href().orElse(""))
                .map(href -> href.split("/"))
                .filter(segments -> segments.length >= 2
                        && family.equals(segments[segments.length - 2]))
                .map(segments -> segments[segments.length - 1].replace('-', '.')).distinct()
                .toList();
    }

    // ------------------------------------------------------------------
    // Lookup plumbing
    // ------------------------------------------------------------------


    private Optional<SdtmVariable> variable(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return product(aStandardName, aStandardVersion)
                .flatMap(product -> dataset(product, aDatasetName))
                .flatMap(dataset -> dataset.datasetVariables().stream()
                        .filter(v -> v.name().filter(aVariableName::equals).isPresent())
                        .findFirst());
    }


    /** The named dataset from the product's class walk, or its model-shaped top level. */
    private static Optional<SdtmDataset> dataset(SdtmProduct aProduct, String aDatasetName)
    {
        for (SdtmClass klass : aProduct.classes())
        {
            for (SdtmDataset dataset : klass.datasets())
            {
                if (dataset.name().filter(aDatasetName::equals).isPresent())
                {
                    return Optional.of(dataset);
                }
            }
        }
        return aProduct.datasets().stream()
                .filter(dataset -> dataset.name().filter(aDatasetName::equals).isPresent())
                .findFirst();
    }


    /** The SDTM Model product behind an IG product, via its {@code _links.model} id. */
    private Optional<SdtmProduct> modelOf(SdtmProduct aIgProduct)
    {
        return aIgProduct.modelLink().flatMap(Link::id)
                .flatMap(modelVersion -> fetchCached("sdtm", modelVersion));
    }


    private Optional<SdtmProduct> product(String aStandardName, String aStandardVersion)
    {
        return fetchCached(productId(aStandardName), aStandardVersion.trim().replace('.', '-'));
    }


    private Optional<SdtmProduct> fetchCached(String aProductId, String aVersion)
    {
        if (aProductId.isEmpty() || aVersion.isEmpty())
        {
            return Optional.empty();
        }
        return products.computeIfAbsent(aProductId + "|" + aVersion, key ->
        {
            try
            {
                return Optional.ofNullable(source.fetch(aProductId, aVersion));
            }
            catch (IOException | RuntimeException e)
            {
                warnOnce(key, e);
                return Optional.empty();
            }
        });
    }


    private Optional<Products> catalog()
    {
        return catalogMemo.computeIfAbsent("catalog", _ ->
        {
            try
            {
                return Optional.ofNullable(source.catalog());
            }
            catch (IOException | RuntimeException e)
            {
                warnOnce("products-catalog", e);
                return Optional.empty();
            }
        });
    }


    private void warnOnce(String aKey, Exception aCause)
    {
        if (warned.add(aKey))
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "CDISC Library lookup failed for {0}; the library-gated Define-XML rules "
                            + "will not fire for it. Cause: {1}",
                    aKey, String.valueOf(aCause));
        }
    }


    /**
     * The Library product id for a define.xml standard name: lower-cased, with the 2.0 CT spellings
     * ({@code SDTM-IG}, {@code SEND-IG}, {@code SEND-IG-AR}, …) folded onto the product ids
     * ({@code sdtmig}, {@code sendig}, {@code sendig-ar}) per the SPI's hyphen-insensitive
     * contract.
     */
    private static String productId(String aStandardName)
    {
        return aStandardName.trim().toLowerCase(Locale.ROOT).replace("sdtm-ig", "sdtmig")
                .replace("send-ig", "sendig");
    }

}
