package com.github;

import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class JsonWriter implements Consumer<Instrumentation.Call> {

    private String path;

    public JsonWriter(String path) {
        this.path = path;
    }

    @Override
    public void accept(Instrumentation.Call call) {
        var result = call.result;
        var args = call.args;
        var resultJson = new Gson().toJson(result);
        var argsJson = new Gson().toJson(args);

        try {
            Files.writeString(Path.of(path + "/" + call.targetName
                    + "_" + call.methodName + "_" + call.timestamp + ".json"), resultJson);
            Files.writeString(Path.of(path + "/" + call.targetName
                    + "_" + call.methodName + "_args_" + call.timestamp + ".json"), argsJson);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}