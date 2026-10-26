package org.matsim.contribs.discrete_mode_choice.pmml;

/**
 * Thrown when a PMML file cannot be read, uses an element this evaluator does
 * not support, or is evaluated with incomplete inputs.
 *
 * @author nkuehnel
 */
public class PmmlException extends RuntimeException {
	public PmmlException(String message) {
		super(message);
	}

	public PmmlException(String message, Throwable cause) {
		super(message, cause);
	}
}
