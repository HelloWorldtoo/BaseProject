package com.example.wj.baseproject.rx;

import android.text.TextUtils;

import com.example.wj.baseproject.BuildConfig;
import com.orhanobut.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.concurrent.TimeUnit;

import okhttp3.Connection;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.http.HttpHeaders;
import okio.Buffer;
import okio.BufferedSource;

/**
 * 网络请求拦截器，添加公共参数，打印网络请求相关信息
 * <p>测试环境时，打印返回数据，会造成多次请求
 * <p>versionName : 版本名</p>
 * <p>platform : 应用平台</p>
 * <p>imei : 手机IMEI</p>
 */
class NetInterceptor implements Interceptor {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * 构造方法
     */
    NetInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {

        // 新的请求
        Request request = chain.request();

        if (!BuildConfig.DEBUG) { // 正式环境直接返回，不打印日志
            return chain.proceed(request);
        }

        RequestBody requestBody = request.body();
        boolean hasRequestBody = requestBody != null;

        Connection connection = chain.connection();
        Protocol protocol = connection != null ? connection.protocol() : Protocol.HTTP_1_1;
        StringBuilder sb = new StringBuilder();
        sb.append("--> ").append(request.method()).append(" ").append(request.url()).append(" ").append(protocol);
        if (hasRequestBody) {
            sb.append(" (").append(requestBody.contentLength()).append("-byte body");
        }

        sb.append("\n");

        if (hasRequestBody) {
            if (requestBody.contentType() != null) {
                sb.append("Content-Type: ").append(requestBody.contentType()).append("\n");
            }
            if (requestBody.contentLength() != -1) {
                sb.append("Content-Length: ").append(requestBody.contentLength()).append("\n");
            }
        }

        Headers headers = request.headers();
        for (int i = 0, count = headers.size(); i < count; i++) {
            String name = headers.name(i);
            if (!"Content-Type".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
                sb.append(name).append(": ").append(headers.value(i)).append("\n");
            }
        }

        if (!hasRequestBody) {
            sb.append("--> END ").append(request.method()).append("\n");
        } else if (bodyEncoded(request.headers())) {
            sb.append("--> END ").append(request.method()).append(" (encoded body omitted)").append("\n");
        } else {
            Buffer buffer = new Buffer();
            requestBody.writeTo(buffer);

            Charset charset = UTF8;
            MediaType contentType = requestBody.contentType();
            if (contentType != null) {
                charset = contentType.charset(UTF8);
            }

            sb.append("\n");
            sb.append(buffer.readString(charset)).append("\n");

            sb.append("--> END ").append(request.method()).append(" (").append(requestBody.contentLength()).append("-byte body)").append("\n");
        }


        if (!showResponse(request.url().toString())) {

            Logger.d(sb.toString());
            return chain.proceed(request);
        }


        long startNs = System.nanoTime();

        // 注意：这里获取响应体，并打印返回数据，会造成多次请求
        Response response = chain.proceed(request);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        ResponseBody responseBody = response.body();
        long contentLength = responseBody.contentLength();
        String bodySize = contentLength != -1 ? contentLength + "-byte" : "unknown-length";
        sb.append("<-- ").append(response.code()).append(" ").append(response.message()).append(" ")
                .append(response.request().url()).append(" (").append(tookMs).append("ms, ")
                .append(bodySize).append(" body)").append("\n");

        Headers headers1 = response.headers();
        for (int i = 0, count = headers1.size(); i < count; i++) {
            sb.append(headers1.name(i)).append(": ").append(headers1.value(i)).append("\n");
        }

        if (!HttpHeaders.hasBody(response)) {
            sb.append("<-- END HTTP").append("\n");
        } else if (bodyEncoded(response.headers())) {
            sb.append("<-- END HTTP (encoded body omitted)").append("\n");
        } else {
            BufferedSource source = responseBody.source();
            source.request(Long.MAX_VALUE); // Buffer the entire body.
            Buffer buffer = source.buffer();

            Charset charset = UTF8;
            MediaType contentType = responseBody.contentType();
            if (contentType != null) {
                try {
                    charset = contentType.charset(UTF8);
                } catch (UnsupportedCharsetException e) {
                    sb.append("\n");
                    sb.append("Couldn't decode the response body; charset is likely malformed.").append("\n");
                    sb.append("<-- END HTTP").append("\n");

                    return response;
                }
            }

            if (contentLength != 0) {
                sb.append("\n");
                String json = buffer.clone().readString(charset);
                sb.append(json).append("\n\n");
                String str = jsonFormat(json);
                if (str.length() > 200) {
                    String start = str.substring(0, 100);
                    String end = str.substring(str.length() - 100);
                    sb.append(start).append("\n")
                            .append("\nThe json was too long...\n\n")
                            .append(end).append("\n");
                } else {
                    sb.append(str).append("\n");
                }
            }

            sb.append("<-- END HTTP (").append(buffer.size()).append("-byte body)").append("\n");
        }

        Logger.d(sb.toString());

        return chain.proceed(request);
    }

    private boolean bodyEncoded(Headers headers) {
        String contentEncoding = headers.get("Content-Encoding");
        return contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity");
    }

    /**
     * 是否打印返回数据
     * <p>打印返回数据会造成多次请求，部分接口不能多次请求</p>
     *
     * @param url 请求url
     * @return 是否打印
     */
    private boolean showResponse(String url) {

        boolean showResponse = true;

        return showResponse;
    }

    /**
     * 将json字符串格式化后返回
     *
     * @param json json字符串
     * @return 格式化后的字符串
     */
    public String jsonFormat(String json) {
        if (TextUtils.isEmpty(json)) {
            return "Empty/Null json content";
        }
        try {
            json = json.trim();
            String message;
            if (json.startsWith("{")) {
                JSONObject jsonObject = new JSONObject(json);
                message = jsonObject.toString(2);
                return message;
            } else if (json.startsWith("[")) {
                JSONArray jsonArray = new JSONArray(json);
                message = jsonArray.toString(2);
                return message;
            } else {
                message = "Invalid Json";
            }
            return message;
        } catch (JSONException e) {
            Logger.e(e.getMessage());
            return "Invalid Json";
        }
    }
}
