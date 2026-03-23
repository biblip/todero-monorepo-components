package com.shellaia.processor.post.responseheader;

import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpResponse;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.postprocessor.PostprocessInfo;
import com.social100.todero.common.postprocessor.PostprocessResult;
import com.social100.todero.common.postprocessor.PostprocessTarget;
import com.social100.todero.common.postprocessor.PostprocessorInterface;
import com.social100.todero.common.postprocessor.PostprocessorMeta;

public class ResponseHeaderPostprocessor implements PostprocessorInterface {
  @Override
  public PostprocessorMeta meta() {
    return new PostprocessorMeta("response-header", "Adds postprocessor headers", 10);
  }

  @Override
  public PostprocessResult after(CommandContext context,
                                 AiatpRequest request,
                                 AiatpResponse response,
                                 PostprocessTarget target,
                                 PostprocessInfo info) {
    com.social100.todero.common.aiatpio.AiatpIO.Headers headers =
        response.getHeaders() == null ? new com.social100.todero.common.aiatpio.AiatpIO.Headers() : copyHeaders(response.getHeaders());
    headers.set("X-Postprocessor-Source", info.source().name());
    headers.set("X-Postprocessor-Success", Boolean.toString(info.success()));
    headers.set("X-Postprocessor-Target", target.component() + ":" + target.command());
    return PostprocessResult.continueWith(response.toBuilder()
        .headers(headers)
        .build());
  }

  private static com.social100.todero.common.aiatpio.AiatpIO.Headers copyHeaders(
      com.social100.todero.common.aiatpio.AiatpIO.Headers source) {
    com.social100.todero.common.aiatpio.AiatpIO.Headers copy = new com.social100.todero.common.aiatpio.AiatpIO.Headers();
    for (java.util.Map.Entry<String, String> entry : source) {
      copy.add(entry.getKey(), entry.getValue());
    }
    return copy;
  }
}
