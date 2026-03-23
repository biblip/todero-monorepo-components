package com.shellaia.processor.pre.authheader;

import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;
import com.social100.todero.common.command.CommandContext;
import com.social100.todero.common.preprocessor.PreprocessResult;
import com.social100.todero.common.preprocessor.PreprocessTarget;
import com.social100.todero.common.preprocessor.PreprocessorInterface;
import com.social100.todero.common.preprocessor.PreprocessorMeta;

import java.nio.file.Path;

public class AuthHeaderPreprocessor implements PreprocessorInterface {
  private final Path baseDir;

  public AuthHeaderPreprocessor(Path baseDir) {
    this.baseDir = baseDir;
  }

  @Override
  public PreprocessorMeta meta() {
    return new PreprocessorMeta("auth-header", "Rejects missing X-Auth header", 10);
  }

  @Override
  public PreprocessResult before(CommandContext context,
                                 AiatpRequest request,
                                 PreprocessTarget target) {
    String token = request.getHeaders().getFirst("X-Auth");
    if (token == null || token.isEmpty()) {
      return PreprocessResult.block(AiatpRuntimeAdapter.failureText("missing_auth_header", "Missing X-Auth header"));
    }
    return PreprocessResult.continueWith(request, target);
  }
}
