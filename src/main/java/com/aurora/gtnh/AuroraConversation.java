package com.aurora.gtnh;

import java.util.ArrayList;
import java.util.List;

/** Thread-safe UI history that is deliberately separate from Minecraft's public chat. */
public final class AuroraConversation {

    public static final class Message {

        public final boolean fromAurora;
        public final String text;

        private Message(boolean fromAurora, String text) {
            this.fromAurora = fromAurora;
            this.text = text;
        }
    }

    private static final List<Message> MESSAGES = new ArrayList<>();
    private static int unread;
    private static long revision;

    private AuroraConversation() {}

    public static synchronized void addPlayer(String text) {
        add(new Message(false, text));
    }

    public static synchronized void addAurora(String text) {
        add(new Message(true, text));
        unread++;
    }

    private static void add(Message message) {
        MESSAGES.add(message);
        while (MESSAGES.size() > 100) MESSAGES.remove(0);
        revision++;
    }

    public static synchronized List<Message> snapshot() {
        return new ArrayList<>(MESSAGES);
    }

    public static synchronized long getRevision() {
        return revision;
    }

    public static synchronized int getUnread() {
        return unread;
    }

    public static synchronized void markRead() {
        unread = 0;
    }
}
