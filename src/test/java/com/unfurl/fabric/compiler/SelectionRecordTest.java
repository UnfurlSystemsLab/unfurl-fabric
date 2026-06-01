package com.unfurl.fabric.compiler;

import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.substrate.api.BindingMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectionRecordTest {
    @Test
    void selectionCarriesArtifactClaimHashBindingAndInterface() {
        SelectionRecord selection = CompilerFixtures.compiled().selections().get(0);

        assertThat(selection.artifact().coordinates()).isEqualTo("com.unfurl:storage-s3:1.0.0");
        assertThat(selection.artifact().sha256()).matches("[0-9a-f]{64}");
        assertThat(selection.claimHash()).matches("[0-9a-f]{64}");
        assertThat(selection.bindingMode()).isEqualTo(BindingMode.IN_PROCESS);
        assertThat(selection.chosenInterfaceKind()).isEqualTo(InterfaceKind.IN_PROCESS);
    }

    @Test
    void validatesPinningFields() {
        SelectionRecord selection = CompilerFixtures.compiled().selections().get(0);

        assertThatThrownBy(() -> new SelectionRecord(selection.artifact(), "ABC", BindingMode.IN_PROCESS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claimHash");
    }
}
