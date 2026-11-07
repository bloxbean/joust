package org.julclang.state.examples.fees;

import org.julclang.state.testkit.Descriptions;
import org.junit.jupiter.api.Test;

/** The JavaScript description of each contract here matches its generated validator (ADR-0013 D3). */
class DescriptionTest {

    @Test
    void descriptionsMatchTheValidators() {
        Descriptions.assertMatches(Pot.class, PotValidator.class);
        Descriptions.assertMatches(Stall.class, StallValidator.class);
    }
}
