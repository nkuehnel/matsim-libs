package org.matsim.contribs.discrete_mode_choice.pmml;

import java.util.Optional;

/**
 * An input of a PMML choice model: a numeric {@code DataField} of the data
 * dictionary. Unit and description come from the {@code Extension} elements
 * {@code unit} and {@code description} (as written by apollo2pmml), so the
 * PMML file carries its own variable contract.
 *
 * @author nkuehnel
 */
public record PmmlVariable(String name, Optional<String> unit, Optional<String> description) {
}
