package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.cdisc.library.api.model.products.Products;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.web.api.dev.MapResource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link CdiscLibraryBackedLibraryProvider} over canned {@link MapResource} products: name/version
 * normalisation (2.0's {@code SDTM-IG} spelling, dotted→dashed versions), the class→dataset→
 * variable walk, c-codes off the codelist link, qualifier labels through the IG's model link
 * (Events/Interventions classes only), published versions off the catalog hrefs, and the
 * conservative empty-answer + once-per-product caching on lookup failure.
 */
class CdiscLibraryBackedLibraryProviderTest
{

    // ------------------------------------------------------------------
    // Canned products
    // ------------------------------------------------------------------

    private static Map<String, Object> variable(String aName, String aLabel, @Nullable String aCore,
            @Nullable String aCodelistHref)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", aName);
        v.put("label", aLabel);
        if (aCore != null)
        {
            v.put("core", aCore);
        }
        if (aCodelistHref != null)
        {
            v.put("_links", Map.of("codelist", Map.of("href", aCodelistHref)));
        }
        return v;
    }


    private static SdtmProduct igProduct()
    {
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("name", "DM");
        dm.put("label", "Demographics");
        dm.put("datasetVariables", List.of(//
                variable("AGE", "Age", "Exp", null), //
                variable("SEX", "Sex", "Req", "/mdr/root/ct/sdtmct/codelists/C66731")));
        Map<String, Object> specialPurpose = new LinkedHashMap<>();
        specialPurpose.put("name", "Special-Purpose");
        specialPurpose.put("datasets", List.of(dm));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG v3.4");
        product.put("version", "3-4");
        product.put("classes", List.of(specialPurpose));
        product.put("_links", Map.of("model", Map.of("href", "/mdr/sdtm/2-0")));
        return MapResource.of(product, SdtmProduct.class);
    }


    private static SdtmProduct modelProduct()
    {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put("name", "Events");
        events.put("classVariables", List.of(variable("--OCCUR", "Occurrence", null, null)));
        Map<String, Object> interventions = new LinkedHashMap<>();
        interventions.put("name", "Interventions");
        interventions.put("classVariables",
                List.of(variable("--DOSFRQ", "Dosing Frequency", null, null)));
        Map<String, Object> findings = new LinkedHashMap<>();
        findings.put("name", "Findings");
        findings.put("classVariables", List.of(variable("--ORRES", "Result", null, null)));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTM v2.0");
        product.put("classes", List.of(events, interventions, findings));
        return MapResource.of(product, SdtmProduct.class);
    }


    private static Products catalog()
    {
        // Real catalog shape: _links.data-tabulation._links.<product> (see Products.sdtmigLinks).
        Map<String, Object> tabulationLinks = Map.of(//
                "sdtmig",
                List.of(Map.of("href", "/mdr/sdtmig/3-1-2"), Map.of("href", "/mdr/sdtmig/3-3"),
                        Map.of("href", "/mdr/sdtmig/3-4")),
                "sendig", List.of(Map.of("href", "/mdr/sendig/3-1")));
        Map<String, Object> products = new LinkedHashMap<>();
        products.put("_links", Map.of("data-tabulation", Map.of("_links", tabulationLinks)));
        return MapResource.of(products, Products.class);
    }


    private static CdiscLibraryBackedLibraryProvider provider()
    {
        return new CdiscLibraryBackedLibraryProvider(
                new CdiscLibraryBackedLibraryProvider.ProductSource()
                {

                    @Override
                    public @Nullable SdtmProduct fetch(String aProductId, String aVersion)
                    {
                        if ("sdtmig".equals(aProductId) && "3-4".equals(aVersion))
                        {
                            return igProduct();
                        }
                        if ("sdtm".equals(aProductId) && "2-0".equals(aVersion))
                        {
                            return modelProduct();
                        }
                        return null;
                    }


                    @Override
                    public Products catalog()
                    {
                        return CdiscLibraryBackedLibraryProviderTest.catalog();
                    }
                });
    }


    @Test
    void servesLabelsCoreAndCCodesFromTheProductWalk()
    {
        CdiscLibraryBackedLibraryProvider provider = provider();
        assertEquals(Optional.of("Demographics"), provider.datasetLabel("SDTMIG", "3.4", "DM"));
        assertEquals(Optional.of("Sex"), provider.variableLabel("SDTMIG", "3.4", "DM", "SEX"));
        assertEquals(Optional.of("Req"),
                provider.variableCoreDesignation("SDTMIG", "3.4", "DM", "SEX"));
        assertEquals(Optional.of("C66731"),
                provider.variableCodelistCCode("SDTMIG", "3.4", "DM", "SEX"));
        assertEquals(Optional.empty(), provider.variableCodelistCCode("SDTMIG", "3.4", "DM", "AGE"),
                "AGE has no codelist link");
        assertEquals(Optional.empty(), provider.datasetLabel("SDTMIG", "3.4", "XX"),
                "unknown dataset answers empty");
        assertEquals(Optional.empty(), provider.datasetLabel("SDTMIG", "9.9", "DM"),
                "unknown version answers empty");
    }


    @Test
    void normalisesTheTwoZeroSpellingsOntoTheProductIds()
    {
        CdiscLibraryBackedLibraryProvider provider = provider();
        assertEquals(Optional.of("Demographics"), provider.datasetLabel("SDTM-IG", "3.4", "DM"),
                "SDTM-IG (2.0 CT spelling) resolves the sdtmig product");
        assertEquals(List.of("3.1.2", "3.3", "3.4"), provider.publishedStandardVersions("SDTM-IG"),
                "hyphenated family name matches the catalog product segment");
    }


    @Test
    void qualifierLabelsComeFromTheModelsEventAndInterventionClassesOnly()
    {
        CdiscLibraryBackedLibraryProvider provider = provider();
        assertEquals(Optional.of("Occurrence"),
                provider.qualifierVariableLabel("SDTMIG", "3.4", "OCCUR"));
        assertEquals(Optional.of("Dosing Frequency"),
                provider.qualifierVariableLabel("SDTMIG", "3.4", "DOSFRQ"));
        assertEquals(Optional.empty(), provider.qualifierVariableLabel("SDTMIG", "3.4", "ORRES"),
                "Findings-class variables are not Event/Intervention qualifiers");
    }


    @Test
    void publishedVersionsComeFromTheCatalogHrefs()
    {
        CdiscLibraryBackedLibraryProvider provider = provider();
        assertEquals(List.of("3.1.2", "3.3", "3.4"), provider.publishedStandardVersions("SDTMIG"));
        assertEquals(List.of("3.1"), provider.publishedStandardVersions("SENDIG"));
        assertEquals(List.of(), provider.publishedStandardVersions("ADaMIG"),
                "families outside the catalog answer empty");
    }


    @Test
    void failingLookupsAnswerEmptyAndAreAttemptedOncePerProduct()
    {
        AtomicInteger fetches = new AtomicInteger();
        CdiscLibraryBackedLibraryProvider provider = new CdiscLibraryBackedLibraryProvider(
                new CdiscLibraryBackedLibraryProvider.ProductSource()
                {

                    @Override
                    public @Nullable SdtmProduct fetch(String aProductId, String aVersion)
                        throws IOException
                    {
                        fetches.incrementAndGet();
                        throw new IOException("offline");
                    }


                    @Override
                    public @Nullable Products catalog() throws IOException
                    {
                        throw new IOException("offline");
                    }
                });
        assertEquals(Optional.empty(), provider.datasetLabel("SDTMIG", "3.4", "DM"));
        assertEquals(Optional.empty(), provider.variableLabel("SDTMIG", "3.4", "DM", "SEX"));
        assertEquals(List.of(), provider.publishedStandardVersions("SDTMIG"));
        assertEquals(1, fetches.get(), "the failed product is cached, not re-fetched");
        assertTrue(provider.datasetLabel("SDTMIG", "3.5", "DM").isEmpty());
        assertEquals(2, fetches.get(), "a different version is its own cache entry");
    }

}
