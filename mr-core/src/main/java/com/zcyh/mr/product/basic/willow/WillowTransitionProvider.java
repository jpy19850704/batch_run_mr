package com.zcyh.mr.product.basic.willow;

import java.util.List;

public interface WillowTransitionProvider {
    List<WillowTransition> getTransitions(int timeIndex, int originNode);
}
