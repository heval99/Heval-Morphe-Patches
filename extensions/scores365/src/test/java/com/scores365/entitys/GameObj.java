package com.scores365.entitys;

import java.util.Date;

/** A test stand-in for 365Scores' GameObj: the kept getters SpoilerFree reads by reflection. */
public class GameObj {
    private final boolean statusThrows;
    private final boolean notStarted;

    public GameObj(boolean statusThrows, boolean notStarted) {
        this.statusThrows = statusThrows;
        this.notStarted = notStarted;
    }

    public int getID() {
        return 1;
    }

    public int getCompetitionID() {
        return 2;
    }

    public Date getSTime() {
        return null;
    }

    public Status getStatusObj() {
        if (statusThrows) throw new IllegalStateException("model changed");
        return new Status(notStarted);
    }

    public static class Status {
        private final boolean notStarted;

        Status(boolean notStarted) {
            this.notStarted = notStarted;
        }

        public boolean getIsNotStarted() {
            return notStarted;
        }
    }
}
