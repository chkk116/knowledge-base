package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_doc_share", indexes = {
        @Index(name = "idx_docshare_owner", columnList = "ownerId"),
        @Index(name = "idx_docshare_status", columnList = "status")
})
public class DocShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Long ownerId;

    /** ACTIVE（可用）/ ARCHIVED（归档） */
    @Column(length = 20)
    private String status = "ACTIVE";

    /** 空间类型：PERSONAL（个人专属，仅所有者可见）/ TEAM（团队共享，可加成员） */
    @Column(length = 20)
    private String type = "TEAM";

    /** 空间容量上限（字节）；仅 TEAM 有意义，null 表示不限制（仅受用户总帽约束） */
    @Column(nullable = true)
    private Long capacity;

    /** 空间主题色（前端色板键，如 indigo/teal/rose），null 由前端按 id 兜底配色 */
    @Column(length = 20)
    private String color;

    @Transient
    private String ownerName;

    @Transient
    private String myPermission;

    /** 成员快照（前 5 个：userId/displayName/avatar），列表页头像堆叠展示用 */
    @Transient
    private java.util.List<java.util.Map<String, Object>> members;

    /** 成员总数（不含所有者） */
    @Transient
    private Long memberCount;

    /** 空间已用字节数（content + yjs_state 之和），由 listMine 注入，用于列表卡片容量展示 */
    @Transient
    private Long used;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getType() { return type; }
    public void setType(String type) { this.type = (type != null && ("PERSONAL".equals(type) || "TEAM".equals(type))) ? type : "TEAM"; }

    public Long getCapacity() { return capacity; }
    public void setCapacity(Long capacity) { this.capacity = capacity; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public String getMyPermission() { return myPermission; }
    public void setMyPermission(String myPermission) { this.myPermission = myPermission; }

    public java.util.List<java.util.Map<String, Object>> getMembers() { return members; }
    public void setMembers(java.util.List<java.util.Map<String, Object>> members) { this.members = members; }

    public Long getMemberCount() { return memberCount; }
    public void setMemberCount(Long memberCount) { this.memberCount = memberCount; }

    public Long getUsed() { return used; }
    public void setUsed(Long used) { this.used = used; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
