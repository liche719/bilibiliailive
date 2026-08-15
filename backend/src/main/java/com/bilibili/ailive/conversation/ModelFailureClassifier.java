package com.bilibili.ailive.conversation;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;

final class ModelFailureClassifier {

    private ModelFailureClassifier() {
    }

    static boolean retryable(Throwable failure) {
        if (hasCause(failure, TimeoutException.class)) {
            return false;
        }
        Integer status = httpStatus(failure);
        return status != null && (status == 429 || status == 502 || status == 503 || status == 504);
    }

    static boolean unsupportedEndpoint(Throwable failure) {
        Integer status = httpStatus(failure);
        return status != null && (status == 404 || status == 405 || status == 501);
    }

    static String displayMessage(Throwable failure) {
        if (hasCause(failure, TimeoutException.class)) {
            return "模型请求超时";
        }
        Integer status = httpStatus(failure);
        if (status != null) {
            return "模型接口返回 HTTP " + status;
        }
        Throwable root = rootCause(failure);
        return "模型调用失败（" + root.getClass().getSimpleName() + "）";
    }

    private static Integer httpStatus(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpException httpException) {
                return httpException.statusCode();
            }
        }
        return null;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }
}
