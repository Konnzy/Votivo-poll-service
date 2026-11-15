package ua.votivo.poll.exception;

public class InvalidPollStateException extends RuntimeException {
    public InvalidPollStateException(String message) { super(message); }
}
