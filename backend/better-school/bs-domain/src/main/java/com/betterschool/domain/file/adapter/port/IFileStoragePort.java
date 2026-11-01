package com.betterschool.domain.file.adapter.port;

/**
 * 对象存储端口：存储图片等静态文件，返回可直接访问的 URL。
 * 由 infrastructure 实现（MinIO），bucket 就绪由实现方保证
 */
public interface IFileStoragePort {

    /**
     * 存储文件
     *
     * @param content     文件字节
     * @param extension   扩展名（小写，不含点；已由调用方校验白名单）
     * @param contentType MIME 类型（已由调用方校验白名单）
     * @return 可公开访问的 URL
     */
    String store(byte[] content, String extension, String contentType);
}
