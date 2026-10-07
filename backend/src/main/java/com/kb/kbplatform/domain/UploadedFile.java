/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 「我的文件」登记记录：用户在文件中心显式上传的文件。
 *
 * 为什么需要这张表：此前文件只是落盘（FileService 直接写到 kb.upload.dir），
 * 没有任何归属信息，管理员端只能靠「扫描目录 + 比对文档正文」反查孤儿文件。
 * 要做文件中心（列表 / 删除 / 复制链接）就必须记录上传者。
 *
 * ⚠️ 只登记「文件中心显式上传」的文件，编辑器内联上传（POST /api/files）不登记 ——
 * 否则正文里的每张配图都会灌进文件中心，且误删会导致文档裂图。
 */
@Entity
@Table(name = "kb_uploaded_file",
        indexes = @Index(name = "idx_uf_owner", columnList = "ownerId"))
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ownerId;                       // 上传者 id

    @Column(length = 512)
    private String url;                         // 可访问相对地址，如 /uploads/2026/10/07/xxx.png

    @Column(length = 255)
    private String name;                        // 原始文件名（展示用）

    private Long size;                          // 字节数

    @Column(length = 128)
    private String contentType;                 // 声明的 MIME（落盘时已做魔数校验）

    private LocalDateTime createdAt = LocalDateTime.now();

    public UploadedFile() {}

    public UploadedFile(Long ownerId, String url, String name, Long size, String contentType) {
        this.ownerId = ownerId;
        this.url = url;
        this.name = name;
        this.size = size;
        this.contentType = contentType;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
