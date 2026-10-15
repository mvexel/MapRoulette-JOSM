// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.IOException;
import java.io.Reader;

/**
 * Read a MapRoulette response body
 *
 * @param <T> The type of the result
 */
@FunctionalInterface
public interface ResponseParser<T> {
    /**
     * Parse the body
     *
     * @param body The response body
     * @return The parsed result
     * @throws IOException if the body could not be read
     */
    T parse(Reader body) throws IOException;
}
