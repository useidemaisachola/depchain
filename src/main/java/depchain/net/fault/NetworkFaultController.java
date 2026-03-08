package depchain.net.fault;

import depchain.net.Message;
import depchain.net.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intrusive fault injection controller for tests/demos.
 *
 * Rules are evaluated on send. A matching rule can:
 * - drop the message
 * - delay delivery
 * - duplicate delivery
 * - corrupt the serialized bytes
 */
public final class NetworkFaultController {

    public enum Action {
        DROP,
        DELAY,
        DUPLICATE,
        CORRUPT
    }

    public static final class SendPlan {
        private boolean drop;
        private long delayMs;
        private int copies;
        private boolean corrupt;

        private SendPlan() {
            this.drop = false;
            this.delayMs = 0;
            this.copies = 1;
            this.corrupt = false;
        }

        public boolean isDrop() {
            return drop;
        }

        public long getDelayMs() {
            return delayMs;
        }

        public int getCopies() {
            return copies;
        }

        public boolean isCorrupt() {
            return corrupt;
        }
    }

    private static final class FaultRule {
        private final String id;
        private final Integer sourceId;
        private final Integer destinationId;
        private final MessageType messageType;
        private final Action action;
        private final long delayMs;
        private final int duplicateCopies;
        private final AtomicInteger remainingUses;

        private FaultRule(String id,
                          Integer sourceId,
                          Integer destinationId,
                          MessageType messageType,
                          Action action,
                          long delayMs,
                          int duplicateCopies,
                          int uses) {
            this.id = id;
            this.sourceId = sourceId;
            this.destinationId = destinationId;
            this.messageType = messageType;
            this.action = action;
            this.delayMs = delayMs;
            this.duplicateCopies = duplicateCopies;
            this.remainingUses = new AtomicInteger(uses);
        }

        private boolean matches(int src, int dst, MessageType type) {
            if (sourceId != null && sourceId != src) {
                return false;
            }
            if (destinationId != null && destinationId != dst) {
                return false;
            }
            if (messageType != null && messageType != type) {
                return false;
            }
            return true;
        }

        private boolean consumeUse() {
            while (true) {
                int current = remainingUses.get();
                if (current < 0) {
                    return true; // infinite uses
                }
                if (current == 0) {
                    return false;
                }
                if (remainingUses.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        private boolean exhausted() {
            int current = remainingUses.get();
            return current == 0;
        }
    }

    private static final List<FaultRule> RULES = new CopyOnWriteArrayList<>();
    private static final AtomicLong IDS = new AtomicLong(0);

    private NetworkFaultController() {
    }

    public static void clearRules() {
        RULES.clear();
    }

    public static List<String> activeRuleIds() {
        List<String> ids = new ArrayList<>();
        for (FaultRule rule : RULES) {
            ids.add(rule.id);
        }
        return ids;
    }

    public static String addDropRule(Integer sourceId, Integer destinationId, MessageType messageType, int uses) {
        return addRule(sourceId, destinationId, messageType, Action.DROP, 0, 0, uses);
    }

    public static String addDelayRule(Integer sourceId, Integer destinationId, MessageType messageType,
                                      long delayMs, int uses) {
        return addRule(sourceId, destinationId, messageType, Action.DELAY, delayMs, 0, uses);
    }

    public static String addDuplicateRule(Integer sourceId, Integer destinationId, MessageType messageType,
                                          int extraCopies, int uses) {
        if (extraCopies < 1) {
            throw new IllegalArgumentException("extraCopies must be >= 1");
        }
        return addRule(sourceId, destinationId, messageType, Action.DUPLICATE, 0, extraCopies, uses);
    }

    public static String addCorruptRule(Integer sourceId, Integer destinationId, MessageType messageType, int uses) {
        return addRule(sourceId, destinationId, messageType, Action.CORRUPT, 0, 0, uses);
    }

    private static String addRule(Integer sourceId,
                                  Integer destinationId,
                                  MessageType messageType,
                                  Action action,
                                  long delayMs,
                                  int duplicateCopies,
                                  int uses) {
        if (uses == 0 || uses < -1) {
            throw new IllegalArgumentException("uses must be -1 (infinite) or > 0");
        }
        String id = "rule-" + IDS.incrementAndGet();
        FaultRule rule = new FaultRule(id, sourceId, destinationId, messageType, action, delayMs, duplicateCopies, uses);
        RULES.add(rule);
        return id;
    }

    public static SendPlan evaluateSend(int sourceId, int destinationId, Message message) {
        Objects.requireNonNull(message, "message");
        SendPlan plan = new SendPlan();
        MessageType messageType = message.getType();

        for (FaultRule rule : RULES) {
            if (!rule.matches(sourceId, destinationId, messageType)) {
                continue;
            }
            if (!rule.consumeUse()) {
                continue;
            }
            apply(plan, rule);
            if (rule.exhausted()) {
                RULES.remove(rule);
            }
        }
        return plan;
    }

    private static void apply(SendPlan plan, FaultRule rule) {
        switch (rule.action) {
            case DROP:
                plan.drop = true;
                break;
            case DELAY:
                plan.delayMs = Math.max(plan.delayMs, rule.delayMs);
                break;
            case DUPLICATE:
                plan.copies = Math.max(plan.copies, 1 + rule.duplicateCopies);
                break;
            case CORRUPT:
                plan.corrupt = true;
                break;
            default:
                throw new IllegalStateException("Unexpected action: " + rule.action);
        }
    }
}
