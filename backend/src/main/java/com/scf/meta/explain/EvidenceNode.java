package com.scf.meta.explain;

import java.util.ArrayList;
import java.util.List;

/**
 * 推导图节点：记录身份确认、依赖关联及字段生成所需的证据。
 * ALL 表示共同必需（缺一不可），ANY 表示可相互替代（任一成立即可），
 * LEAF 为可展开到原始位置的原子证据。互斥身份候选各自成支（exclusive=true 的 ANY），
 * 不同候选下的边不得合并证明共同可达。
 */
public class EvidenceNode {

    public String id;
    /** ALL / ANY / LEAF */
    public String type;
    /** EDGE / SUPPORT / BUILD_RECORD / RELATION_TEXT / IDENTITY / VALIDITY / CONDITION / ABSENCE */
    public String role;
    public String label;
    /** ANY 节点：子分支是否互斥（互斥身份候选） */
    public Boolean exclusive;

    /** LEAF：证据对象类型 CLAIM / SOURCE / SIGNATURE / CATALOG_REVISION / CONDITION */
    public String refType;
    public String refId;
    /** 原始位置：来源地址 + 载荷路径 */
    public String location;
    /** 业务有效区间显示文本 */
    public String interval;
    /** 互斥假设：该证据仅在对应身份候选下成立（组键|候选标识） */
    public List<String> assumptions;

    public List<EvidenceNode> children;

    public static EvidenceNode all(String role, String label, List<EvidenceNode> children) {
        EvidenceNode n = new EvidenceNode();
        n.type = "ALL";
        n.role = role;
        n.label = label;
        n.children = new ArrayList<>(children);
        return n;
    }

    public static EvidenceNode any(String role, String label, boolean exclusive, List<EvidenceNode> children) {
        EvidenceNode n = new EvidenceNode();
        n.type = "ANY";
        n.role = role;
        n.label = label;
        n.exclusive = exclusive ? true : null;
        n.children = new ArrayList<>(children);
        return n;
    }

    public static EvidenceNode leaf(String role, String label, String refType, String refId,
                                    String location, String interval) {
        EvidenceNode n = new EvidenceNode();
        n.type = "LEAF";
        n.role = role;
        n.label = label;
        n.refType = refType;
        n.refId = refId;
        n.location = location;
        n.interval = interval;
        return n;
    }
}
