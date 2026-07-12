package com.sw.yang.springlea.ioc.beancreation.exp04;

/**
 * FactoryBean 的产品对象。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
public class Car {

    private final String model;

    public Car(String model) {
        this.model = model;
    }

    public String drive() {
        return "Driving " + model;
    }

    public String getModel() {
        return model;
    }
}
