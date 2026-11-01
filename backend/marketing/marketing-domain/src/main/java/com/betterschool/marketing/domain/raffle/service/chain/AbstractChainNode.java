package com.betterschool.marketing.domain.raffle.service.chain;

/** 链节点的链表骨架：只管 next 指针，决策逻辑在实现里。 */
public abstract class AbstractChainNode implements IChainNode {

    private IChainNode nextNode;

    @Override
    public IChainNode next() {
        return nextNode;
    }

    @Override
    public IChainNode appendNext(IChainNode nextNode) {
        this.nextNode = nextNode;
        return nextNode;
    }
}
