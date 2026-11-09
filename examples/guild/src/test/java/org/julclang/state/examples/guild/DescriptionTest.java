package org.julclang.state.examples.guild;

import org.julclang.state.testkit.Descriptions;
import org.junit.jupiter.api.Test;

/** The JavaScript description of each contract here matches its generated validator (ADR-0013 D3). */
class DescriptionTest {

    @Test
    void descriptionsMatchTheValidators() {
        Descriptions.assertMatches(Armory.class, ArmoryValidator.class);
        Descriptions.assertMatches(Guild.class, GuildValidator.class);
    }
}
