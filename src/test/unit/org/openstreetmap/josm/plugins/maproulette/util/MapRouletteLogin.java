// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
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
            MapRouletteClient.getInstance().setAuthProvider(new AuthProvider() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public Set<Capability> capabilities() {
                    return EnumSet.allOf(Capability.class);
                }

                @Override
                public void authenticate(HttpClient client) {
                    // The test server doesn't check credentials
                }
            });
        }

        @Override
        public void afterEach(ExtensionContext context) {
            MapRouletteClient.getInstance().setAuthProvider(AuthProvider.NONE);
        }
    }
}
