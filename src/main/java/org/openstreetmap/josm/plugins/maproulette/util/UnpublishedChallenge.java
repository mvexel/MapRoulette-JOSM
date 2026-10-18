// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;

import org.openstreetmap.josm.plugins.maproulette.api.ChallengeAPI;
import org.openstreetmap.josm.plugins.maproulette.api.ProjectAPI;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.api.model.Project;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Say which part of an unpublished challenge is disabled (#116). MapRoulette doesn't say: it refuses a super-user's
 * Start with {@code challenge_draft}, and answers everyone else with a 404 for the task. Reading the challenge and its
 * project, which works without a login even when they are disabled, tells which one is.
 */
public final class UnpublishedChallenge {
    private UnpublishedChallenge() {
        // Hide constructor
    }

    /**
     * Explain why a challenge's tasks can't be started, if the challenge isn't published. Call off the EDT.
     *
     * @param challengeId The challenge
     * @return The explanation, or {@code null} if the challenge and its project are enabled, or can't be read
     */
    @Nullable
    public static String explain(long challengeId) {
        try {
            final var challenge = ChallengeAPI.challenge(challengeId);
            final var project = ProjectAPI.get(challenge.general().parent());
            return text(challenge, project);
        } catch (IOException e) {
            // The challenge is unknown too, or the server can't be reached: the original error says more
            Logging.debug(e);
            return null;
        }
    }

    /**
     * Explain why a challenge's tasks can't be started
     *
     * @param challenge The challenge
     * @param project   Its project
     * @return The explanation, or {@code null} if both are enabled
     */
    @Nullable
    static String text(@Nonnull Challenge challenge, @Nonnull Project project) {
        final var challengeOn = challenge.general().enabled();
        if (challengeOn && project.enabled()) {
            return null;
        }
        final var name = challenge.name();
        final var projectName = Utils.isStripEmpty(project.displayName()) ? project.name() : project.displayName();
        if (challengeOn) {
            return tr("Challenge \"{0}\" isn''t published yet: its project \"{1}\" is disabled. Only its owner can "
                    + "enable it.", name, projectName);
        } else if (project.enabled()) {
            return tr("Challenge \"{0}\" isn''t published yet. Only its owner can publish it.", name);
        }
        return tr("Challenge \"{0}\" isn''t published yet, and its project \"{1}\" is disabled. Only their owner can "
                + "publish them.", name, projectName);
    }
}
