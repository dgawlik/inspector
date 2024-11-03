package com.github;

import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class JsonWriter implements Consumer<Instrumentation.CallResult> {

    private String path;

    public JsonWriter(String path) {
        this.path = path;
    }

    @Override
    public void accept(Instrumentation.CallResult callResult) {
        var result = callResult.result;
        var resultJson = new Gson().toJson(result);

        try {
            Files.writeString(Path.of(path + "/" + callResult.targetName + "_" + callResult.methodName + ".json"), resultJson);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}