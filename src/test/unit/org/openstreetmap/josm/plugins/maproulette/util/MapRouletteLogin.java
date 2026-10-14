// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.tools.HttpClient;

/**
 * Pretend that the user can log in to MapRoulette (the requests carry no credentials)
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(MapRouletteLogin.Extension.class)
public @interface MapRouletteLogin {
    class Extension implements BeforeEachCallback, AfterEachCallback {
        @Override
        public void beforeEach(ExtensionContext context) {
            MapRouletteAuth.setProvider(new AuthProvider() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public void authenticate(HttpClient client) {
                    // The test server doesn't check credentials
                }
            });
        }

        @Override
        public void afterEach(ExtensionContext context) {
            MapRouletteAuth.setProvider(AuthProvider.NONE);
        }
    }
}
