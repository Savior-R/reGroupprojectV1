package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Recording access rules (05B §8).
 *
 * <p>Owner students may upload and read their own temporary recording; teachers may read only a
 * submitted session in their course group; administrators are always forbidden. These are the
 * session rules from {@link SessionAccessPolicy}, so this policy delegates instead of duplicating
 * the course/school checks.
 */
@Component
public class RecordingAccessPolicy {

    private final SessionAccessPolicy sessionAccessPolicy;

    public RecordingAccessPolicy(SessionAccessPolicy sessionAccessPolicy) {
        this.sessionAccessPolicy = Objects.requireNonNull(
                sessionAccessPolicy, "sessionAccessPolicy");
    }

    /** Read access for the recording list: owner student or submitted-session course teacher. */
    public void requireRead(CurrentUser user, Session session) {
        sessionAccessPolicy.requireRead(user, session);
    }

    /** Upload access: only the student who owns the session. */
    public void requireUpload(CurrentUser user, Session session) {
        sessionAccessPolicy.requireOwner(user, session);
    }
}
