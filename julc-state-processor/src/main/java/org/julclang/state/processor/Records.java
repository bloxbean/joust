package org.julclang.state.processor;

import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import java.util.List;

/** Record components, read reliably. */
public final class Records {

    private Records() {
    }

    /**
     * {@code type}'s record components. javac's {@code ClassSymbol.getRecordComponents()} does not complete a symbol
     * read from a class file, so a record from a jar can report none (its description then lost its fields, ADR-0015
     * S1). Reading the members first completes it.
     */
    public static List<? extends RecordComponentElement> components(TypeElement type) {
        type.getEnclosedElements();
        return type.getRecordComponents();
    }
}
