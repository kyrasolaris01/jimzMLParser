package com.alanmrace.jimzmlparser.event;

import com.alanmrace.jimzmlparser.mzml.CVParam;

/**
 * Event triggered when the value of a CV parameter is changed.
 * 
 * @author alan.race
 */
public class ValueCVParamChangeEvent<T> extends CVParamChangeEvent {
    private final T oldValue;
    private final T newValue;
    
    /**
     * Create event describing a change to the value of a CV parameter.
     *
     * @param source Source of the event
     * @param oldValue Previous value
     * @param newValue New value
     */
    public ValueCVParamChangeEvent(CVParam source, T oldValue, T newValue) {
        super(source);
        
        this.oldValue = oldValue;
        this.newValue = newValue;
    }
    
    /**
     * Get the previous value prior to the event.
     *  
     * @return Previous value
     */
    public T getOldValue() {
        return oldValue;
    }
    
    /**
     * Get the new value after to the event.
     *  
     * @return New value
     */
    public T getNewValue() {
        return newValue;
    }
}
