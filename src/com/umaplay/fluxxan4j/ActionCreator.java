package com.umaplay.fluxxan4j;

/**
 * An Action that can be passed to {@link Fluxxan#Fluxxan(Object, ActionCreator)}
 */
public interface ActionCreator {
    /**
     * A convenience method to inject the dispatcher into the action
     * @param dispatcher The dispatcher
     */
    public void setDispatcher(Dispatcher dispatcher);
}
