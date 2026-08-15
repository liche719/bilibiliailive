package com.bilibili.ailive.tts;

import java.nio.file.Path;

public interface LocalSpeechSynthesizer {

    void synthesize(String text, Path output, String voice, int rate) throws Exception;
}
