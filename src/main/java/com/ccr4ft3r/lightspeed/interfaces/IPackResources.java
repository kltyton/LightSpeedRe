package com.ccr4ft3r.lightspeed.interfaces;

import java.util.Map;

public interface IPackResources extends ICache {

    Map<String, Boolean> lightspeed$getExistenceByResource();

    void lightspeed$setExistenceByResource(Map<String, Boolean> existenceByResource);
}