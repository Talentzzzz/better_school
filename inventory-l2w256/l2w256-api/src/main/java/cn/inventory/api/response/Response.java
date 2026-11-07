package cn.inventory.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应体 {code, info, data}
 *
 * <p>保留 getter/setter 与无参构造：这一层后续要换成 dubbo 暴露时，
 * 需要能被序列化框架（fastjson2 / hessian）无参构造 + setter 反序列化。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Response<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private String code;
    private String info;
    private T data;

    public static <T> Response<T> success(T data) {
        return new Response<>("0000", "调用成功", data);
    }

    public static <T> Response<T> success(String code, String info, T data) {
        return new Response<>(code, info, data);
    }

    public static <T> Response<T> fail(String code, String info) {
        return new Response<>(code, info, null);
    }
}
