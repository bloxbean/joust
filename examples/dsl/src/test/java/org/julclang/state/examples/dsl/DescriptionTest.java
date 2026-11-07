package org.julclang.state.examples.dsl;

import org.julclang.state.testkit.Descriptions;
import org.junit.jupiter.api.Test;

/** The JavaScript description of each contract here matches its generated validator (ADR-0013 D3). */
class DescriptionTest {

    @Test
    void descriptionsMatchTheValidators() {
        Descriptions.assertMatches(Guards.class, GuardsValidator.class);
        Descriptions.assertMatches(Jar.class, JarValidator.class);
        Descriptions.assertMatches(Points.class, PointsValidator.class);
    }
}
