package com.smartstaff.proctoring;

import com.smartstaff.entity.AttemptEvent;
import com.smartstaff.entity.AttemptEventType;
import com.smartstaff.repository.AttemptEventRepository;
import com.smartstaff.util.ProctoringRules;
import com.smartstaff.util.ProctoringRules.Stamped;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Reads an attempt's stored events: the authoritative warning count and the F10 integrity concern. */
@Service
public class ProctoringService {

    private final AttemptEventRepository events;

    public ProctoringService(AttemptEventRepository events) {
        this.events = events;
    }

    @Transactional(readOnly = true)
    public int warnings(UUID attemptId) {
        return ProctoringRules.countWarnings(stamped(events.findByAttemptIdOrderByAtAsc(attemptId)));
    }

    @Transactional(readOnly = true)
    public boolean concern(UUID attemptId) {
        List<AttemptEvent> all = events.findByAttemptIdOrderByAtAsc(attemptId);
        long multiple = all.stream().filter(e -> e.getType() == AttemptEventType.MULTIPLE_FACES).count();
        long phones = all.stream().filter(e -> e.getType() == AttemptEventType.PHONE_DETECTED).count();
        long awaySeconds = all.stream()
                .filter(e -> e.getType() == AttemptEventType.NO_FACE || e.getType() == AttemptEventType.LOOKING_AWAY)
                .mapToLong(e -> seconds(e.getPayload().get("seconds"))).sum();
        return ProctoringRules.concern(ProctoringRules.countWarnings(stamped(all)), multiple, phones, awaySeconds);
    }

    private static List<Stamped> stamped(List<AttemptEvent> all) {
        return all.stream().map(e -> new Stamped(e.getType(), e.getAt())).toList();
    }

    private static long seconds(Object v) {
        if (v instanceof Number n) return Math.max(0, Math.min(n.longValue(), 3600));
        return 0;
    }
}
