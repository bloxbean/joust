package org.julclang.state.examples.upgrade;

import org.julclang.state.testkit.Descriptions;
import org.junit.jupiter.api.Test;

/** The JavaScript description of each contract here matches its generated validator (ADR-0013 D3). */
class DescriptionTest {

    @Test
    void descriptionsMatchTheValidators() {
        Descriptions.assertMatches(CounterV1.class, CounterV1Validator.class);
        Descriptions.assertMatches(CounterV2.class, CounterV2Validator.class);
    }
}
