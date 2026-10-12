package com.example.protocol

import com.example.p2p.PeerRecord
import org.json.JSONArray
import org.json.JSONObject

enum class DhtRpcType {
    PING,
    PONG,
    FIND_NODE,
    NODES_RESPONSE,
    PUT_VALUE,
    GET_VALUE,
    VALUE_RESPONSE
}

data class DhtEnvelope(
    val rpcId: String,
    val type: DhtRpcType,
    val senderPeerId: String,
    val targetPeerId: String? = null,
    val peerRecord: PeerRecord? = null,
    val nodes: List<DhtNodeItem> = emptyList(),
    val observedAddress: String? = null
) {
    data class DhtNodeItem(val peerId: String, val addresses: List<String>)

    fun toJson(): String {
        val json = JSONObject()
        json.put("rpcId", rpcId)
        json.put("type", type.name)
        json.put("senderPeerId", senderPeerId)
        targetPeerId?.let { json.put("targetPeerId", it) }
        peerRecord?.let { json.put("peerRecord", it.toJson()) }
        observedAddress?.let { json.put("observedAddress", it) }

        if (nodes.isNotEmpty()) {
            val arr = JSONArray()
            for (node in nodes) {
                val nodeObj = JSONObject()
                nodeObj.put("peerId", node.peerId)
                val addrsArr = JSONArray()
                node.addresses.forEach { addrsArr.put(it) }
                nodeObj.put("addresses", addrsArr)
                arr.put(nodeObj)
            }
            json.put("nodes", arr)
        }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): DhtEnvelope? {
            return try {
                val json = JSONObject(jsonStr)
                val rpcId = json.getString("rpcId")
                val type = DhtRpcType.valueOf(json.getString("type"))
                val sender = json.getString("senderPeerId")
                val target = if (json.has("targetPeerId")) json.getString("targetPeerId") else null
                val recordStr = if (json.has("peerRecord")) json.getString("peerRecord") else null
                val record = recordStr?.let { PeerRecord.fromJson(it) }
                val observed = if (json.has("observedAddress")) json.getString("observedAddress") else null

                val nodes = mutableListOf<DhtNodeItem>()
                val arr = json.optJSONArray("nodes")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val nodeObj = arr.getJSONObject(i)
                        val pid = nodeObj.getString("peerId")
                        val aArr = nodeObj.getJSONArray("addresses")
                        val addrs = mutableListOf<String>()
                        for (j in 0 until aArr.length()) {
                            addrs.add(aArr.getString(j))
                        }
                        nodes.add(DhtNodeItem(pid, addrs))
                    }
                }

                DhtEnvelope(rpcId, type, sender, target, record, nodes, observed)
            } catch (_: Exception) {
                null
            }
        }
    }
}
