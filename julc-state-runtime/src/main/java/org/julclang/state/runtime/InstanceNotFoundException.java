package org.julclang.state.runtime;

/** No UTxO holds the instance's thread token: it was never created, or it was destroyed. */
public class InstanceNotFoundException extends JoustException {
    public InstanceNotFoundException(String message) {
        super(message);
    }
}
