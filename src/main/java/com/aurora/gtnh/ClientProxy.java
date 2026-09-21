package com.aurora.gtnh;

public final class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        AuroraClient.start();
    }
}
