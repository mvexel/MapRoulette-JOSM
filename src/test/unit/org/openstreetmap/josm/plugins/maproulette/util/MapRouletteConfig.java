// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.net.HttpURLConnection.HTTP_OK;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Body;
import com.github.tomakehurst.wiremock.http.ContentTypeHeader;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.util.ReflectionUtils;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.HTTP;
import org.openstreetmap.josm.tools.JosmRuntimeException;
import org.openstreetmap.josm.tools.Logging;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// Extensions run their afterEach in reverse order, so the worker drains before WireMock stops
@ExtendWith({ MapRouletteConfig.Extension.class, MapRouletteConfig.WorkerDrain.class })
@HTTP
@OsmUser
public @interface MapRouletteConfig {
    class Extension extends WireMockExtension {
        /**
         * Get the directory for test resources. Needed since Jenkins will run in one directory up
         * @return The base directory for test resources.
         */
        private static String getDirectory() {
            final var basePath = Paths.get("src", "test", "resources");
            final var maproulette = Path.of("MapRoulette");
            if (Files.isDirectory(maproulette) && Files.isDirectory(maproulette.resolve(basePath))) {
                return maproulette.resolve(basePath).toString();
            }
            return basePath.toString();
        }

        public Extension() {
            super(extensionOptions().options(options().dynamicPort().usingFilesUnderDirectory(getDirectory())
                    .extensions(services -> List.of(new MapRouletteExtension(services.getFiles())))));
        }

        @Override
        protected void onBeforeEach(WireMockRuntimeInfo wireMockRuntimeInfo) {
            org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig
                    .setInstance(new org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig(
                            wireMockRuntimeInfo.getHttpBaseUrl() + "/api/v2"));
            Config.getPref().put("osm-server.url", wireMockRuntimeInfo.getHttpBaseUrl() + "/api");
        }

        @Override
        protected void onAfterEach(WireMockRuntimeInfo wireMockRuntimeInfo) {
            Config.getPref().put("osm-server.url", null);
            ModifiedObjects.getModifiedTasks().forEach(ModifiedObjects::removeModifiedTask);
            ModifiedObjects.getLockedTasks().forEach(ModifiedObjects::removeLockedTask);
            try {
                final Field instance = org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.class
                        .getDeclaredField("instance");
                ReflectionUtils.makeAccessible(instance);
                instance.set(null, null);
            } catch (ReflectiveOperationException exception) {
                throw new JosmRuntimeException(exception);
            }
        }
    }

    /**
     * Let background requests (such as a challenge prefetch) finish against this test's server. A request still open
     * when WireMock stops hangs until its read timeout and blocks the worker for the tests that follow.
     */
    class WorkerDrain implements AfterEachCallback {
        @Override
        public void afterEach(ExtensionContext context) throws Exception {
            try {
                MainApplication.worker.submit(() -> {
                    /* Sync worker */ }).get(10, TimeUnit.SECONDS);
            } catch (TimeoutException timeoutException) {
                Logging.warn("The worker is still busy after " + context.getDisplayName());
            }
        }
    }

    /**
     * A wiremock extension that allows us to return responses for maproulette APIs
     */
    class MapRouletteExtension implements ResponseDefinitionTransformerV2 {
        private final FileSource files;

        public MapRouletteExtension(FileSource files) {
            Objects.requireNonNull(files, "files");
            this.files = files;
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            final LoggedRequest request = serveEvent.getRequest();
            final ResponseDefinition responseDefinition = serveEvent.getResponseDefinition();
            if (request.getUrl().startsWith("/api/v2/task/") && request.getUrl().matches("/api/v2/task/\\d+")) {
                final var file = files.getTextFileNamed(request.getUrl().substring(1) + "/start");
                // Only tasks with a recorded start response; other stubs (and missing files) pass through
                if (file != null && file.getPath() != null && new File(file.getPath()).isFile()) {
                    final var builder = responseDefinition.wasConfigured()
                            ? ResponseDefinitionBuilder.like(responseDefinition)
                            : ResponseDefinitionBuilder.responseDefinition();
                    return builder.withStatus(HTTP_OK)
                            .withResponseBody(
                                    Body.ofBinaryOrText(file.readContents(), new ContentTypeHeader("application/json")))
                            .build();
                }
            }
            return responseDefinition;
        }

        @Override
        public String getName() {
            return "MapRouletteExtension";
        }
    }
}
