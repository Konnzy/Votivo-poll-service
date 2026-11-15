package ua.votivo.poll.exception;

public class PollNotFoundException extends RuntimeException {
    public PollNotFoundException() { super("Poll not found"); }
}
