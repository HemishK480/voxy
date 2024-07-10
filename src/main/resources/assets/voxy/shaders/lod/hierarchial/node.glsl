
layout(binding = NODE_DATA_INDEX, std430) restrict buffer NodeData {
//Needs to be read and writeable for marking data,
//(could do an evil violation, make this readonly, then have a writeonly varient, which means that writing might not be visible but will show up by the next frame)
//Nodes are 16 bytes big (or 32 cant decide, 16 might _just_ be enough)
    ivec4[] nodes;
};

//First 2 are joined to be the position




//All node access and setup into global variables
//TODO: maybe make it global vars
struct UnpackedNode {
    uint nodeId;

    ivec3 pos;
    uint lodLevel;

    uint flags;

    uint meshPtr;
    uint childPtr;
};

#define NULL_NODE ((1<<25)-1)
#define NULL_MESH ((1<<24)-1)

void unpackNode(inout UnpackedNode node, uint nodeId) {
    node.nodeId = nodeId;
    uvec4 compactedNode = nodes[nodeId];
    node.lodLevel = compactedNode.x >> 28;

    {
        int y = ((int(compactedNode.x)<<4)>>24);
        int x = (int(compactedNode.y)<<4)>>8;
        int z = int((int(compactedNode.x)&((1<<20)-1))<<4);
        z |= int(compactedNode.y>>28);
        z <<= 8;
        z >>= 8;

        node.pos = ivec3(x, y, z);
    }

    node.meshPtr = compactedNode.z&0xFFFFFFu;
    node.childPtr = compactedNode.w&0x1FFFFFFu;
    node.flags = (compactedNode.z>>24) | ((compactedNode.w>>23)<<8);
}

bool hasMesh(in UnpackedNode node) {
    return node.meshPtr != NULL_MESH;
}

bool hasChildren(in UnpackedNode node) {
    return node.childPtr != NULL_NODE;
}

bool isEmpty(in UnpackedNode node) {
    return (node.flags&2u) != 0;
}

bool hasRequested(in UnpackedNode node) {
    return (node.flags&1u) != 0u;
}

uint getMesh(in UnpackedNode node) {
    return node.meshPtr;
}

uint getId(in UnpackedNode node) {
    return node.nodeId;
}

uint getChild(in UnpackedNode node) {
    return node.flags >> 2;
}

//-----------------------------------

void markRequested(inout UnpackedNode node) {
    node.flags |= 1u;
    nodes[node.nodeId].z |= 1u<<24;
}