package com.mwang.engine;

import com.mwang.model.Order;
import com.mwang.model.Trade;

import java.util.List;

public interface MatchingEngine {
    List<Trade> submit(Order incoming);
}
