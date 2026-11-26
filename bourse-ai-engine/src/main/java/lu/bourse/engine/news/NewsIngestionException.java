package lu.bourse.engine.news;

import org.springframework.http.HttpStatus;

public final class NewsIngestionException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;
    private final long retryAfterSeconds;
    public NewsIngestionException(String code,HttpStatus status,boolean retryable,long retryAfterSeconds) {
        super(code);
        this.code=code; this.status=status; this.retryable=retryable;
        this.retryAfterSeconds=retryAfterSeconds;
    }
    public String code(){return code;}
    public HttpStatus status(){return status;}
    public boolean retryable(){return retryable;}
    public long retryAfterSeconds(){return retryAfterSeconds;}
}
