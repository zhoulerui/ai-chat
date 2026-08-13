package com.example.aichat.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * 本地 BGE 中文嵌入模型(ONNX + onnxruntime + DJL tokenizers,纯 Java,免装 Ollama)。
 * bge-small-zh-v1.5:输出 512 维;sentence embedding 取 CLS 位置(last_hidden_state[0]);
 * 检索查询需加官方指令前缀,文档内容不加(与 bge 官方用法一致)。
 */
public class LocalBgeEmbeddingModel implements EmbeddingModel {

    /** bge 官方检索查询前缀(文档入库不加,检索查询加) */
    public static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章:";
    private static final int MAX_LENGTH = 512;

    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public LocalBgeEmbeddingModel(String modelPath, String tokenizerPath) throws Exception {
        if (!Files.exists(Path.of(modelPath))) {
            throw new IllegalStateException(
                    "模型文件不存在:" + modelPath + System.lineSeparator()
                    + "请检查:" + System.lineSeparator()
                    + "  1) 本地开发:启动时加 --spring.profiles.active=local(模型在本机 D:/self-project/LLM-demo/.tmp/bge/ 下);" + System.lineSeparator()
                    + "  2) 服务器部署:先把模型上传到该路径(下载命令见设计文档 5.1);" + System.lineSeparator()
                    + "  3) 也可用环境变量 AI_MODEL_PATH / AI_TOKENIZER_PATH 指定实际路径。");
        }
        if (!Files.exists(Path.of(tokenizerPath))) {
            throw new IllegalStateException("分词器文件不存在:" + tokenizerPath
                    + "(与模型同一目录,下载命令见设计文档 5.1)");
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        session = env.createSession(modelPath, new OrtSession.SessionOptions());
        tokenizer = HuggingFaceTokenizer.newInstance(Path.of(tokenizerPath),
                Map.of("padding", "true", "truncation", "true", "maxLength", String.valueOf(MAX_LENGTH)));
    }

    /** 文档入库:不加前缀 */
    @Override
    public float[] embed(String text) {
        return embedInternal(text);
    }

    /** 检索查询:加 bge 官方前缀(供 RagService 手动拼 query 时使用) */
    public float[] embedQuery(String query) {
        return embedInternal(QUERY_PREFIX + query);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> list = new ArrayList<>();
        List<float[]> all = embed(request.getInstructions());
        for (int i = 0; i < all.size(); i++) {
            list.add(new Embedding(all.get(i), i));
        }
        return new EmbeddingResponse(list);
    }

    private float[] embedInternal(String text) {
        try {
            Encoding encoding = tokenizer.encode(text, true);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();

            Map<String, OnnxTensor> inputs = new HashMap<>();
            for (String name : session.getInputNames()) {
                switch (name) {
                    case "input_ids" ->
                        inputs.put(name, OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][]{ids}));
                    case "attention_mask" ->
                        inputs.put(name, OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][]{mask}));
                    case "token_type_ids" ->
                        inputs.put(name, OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][]{new long[ids.length]}));
                    default -> { /* 忽略未知输入 */ }
                }
            }
            if (inputs.isEmpty()) {
                throw new IllegalStateException("无法识别的 ONNX 输入节点:" + session.getInputNames());
            }

            try (OrtSession.Result result = session.run(inputs)) {
                String outName = session.getOutputNames().iterator().next();
                float[][][] hidden = (float[][][]) result.get(outName).orElseThrow().getValue();
                return normalize(hidden[0][0]); // CLS pooling
            }
        } catch (OrtException e) {
            throw new RuntimeException("BGE 嵌入失败", e);
        }
    }

    private float[] normalize(float[] v) {
        double sum = 0;
        for (float f : v) {
            sum += f * f;
        }
        double norm = Math.sqrt(sum);
        if (norm < 1e-9) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }
}
