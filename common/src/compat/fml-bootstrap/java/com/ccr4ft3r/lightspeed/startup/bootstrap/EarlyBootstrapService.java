package com.ccr4ft3r.lightspeed.startup.bootstrap;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.Set;
import org.slf4j.Logger;

public abstract class EarlyBootstrapService implements ITransformationService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Path instance;

    protected EarlyBootstrapService() {
        // Discovery has already established FMLPaths before service providers are instantiated.
        instance = BootstrapGamePaths.gameDirectory().toAbsolutePath().normalize();
        try {
            EarlyBootstrap.start(instance, BootstrapGamePaths.configFile());
        } catch (Exception | LinkageError exception) {
            System.setProperty("lightspeed.bootstrapAgent.failure", exception.toString());
            LOGGER.error("Lightspeed early bootstrap activation failed", exception);
        }
    }

    @Override
    public String name() {
        return "lightspeed";
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) throws IncompatibleEnvironmentException {
        if (!otherServices.contains("fml")) {
            throw new IncompatibleEnvironmentException("Lightspeed early bootstrap requires FML");
        }
    }

    @Override
    public void initialize(IEnvironment environment) {
        Path published = environment.getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElseThrow(() -> new IllegalStateException("FML did not publish the current game directory"));
        if (!published.toAbsolutePath().normalize().equals(instance)) {
            throw new IllegalStateException("FML service discovery and game arguments identify different instances");
        }
    }

}
