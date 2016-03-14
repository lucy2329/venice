package com.linkedin.venice.listener;

import io.netty.handler.codec.http.HttpResponseStatus;


/**
 * Created by mwise on 3/11/16.
 */
public class HttpError {
  private final String message;
  private final HttpResponseStatus status;

  public HttpError(String message, HttpResponseStatus status){
    this.message = message;
    this.status = status;
  }

  public String getMessage() {
    return message;
  }

  public HttpResponseStatus getStatus() {
    return status;
  }
}
