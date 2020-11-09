package mezz.jei.startup;

import com.google.common.base.Preconditions;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.ModIds;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.config.BookmarkConfig;
import mezz.jei.config.EditModeConfig;
import mezz.jei.config.IClientConfig;
import mezz.jei.config.IEditModeConfig;
import mezz.jei.config.IngredientFilterConfig;
import mezz.jei.config.JEIClientConfig;
import mezz.jei.config.KeyBindings;
import mezz.jei.config.ModIdFormattingConfig;
import mezz.jei.config.sorting.IngredientTypeSortingConfig;
import mezz.jei.config.sorting.ModNameSortingConfig;
import mezz.jei.config.WorldConfig;
import mezz.jei.config.sorting.RecipeCategorySortingConfig;
import mezz.jei.events.EventBusHelper;
import mezz.jei.events.PlayerJoinedWorldEvent;
import mezz.jei.gui.textures.Textures;
import mezz.jei.ingredients.ForgeModIdHelper;
import mezz.jei.ingredients.IIngredientSorter;
import mezz.jei.ingredients.IngredientSorter;
import mezz.jei.util.AnnotatedInstanceUtil;
import mezz.jei.util.ErrorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.resources.IResourceManager;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.resource.IResourceType;
import net.minecraftforge.resource.ISelectiveResourceReloadListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;

public class ClientLifecycleHandler {
	private final Logger LOGGER = LogManager.getLogger();
	private final JeiStarter starter = new JeiStarter();
	private final Textures textures;
	private final IClientConfig clientConfig;
	private final BookmarkConfig bookmarkConfig;
	private final ModIdFormattingConfig modIdFormattingConfig;
	private final IngredientFilterConfig ingredientFilterConfig;
	private final WorldConfig worldConfig;
	private final IModIdHelper modIdHelper;
	private final IEditModeConfig editModeConfig;
	private final RecipeCategorySortingConfig recipeCategorySortingConfig;
	private final IIngredientSorter ingredientSorter;

	public ClientLifecycleHandler(NetworkHandler networkHandler, Textures textures) {
		File jeiConfigurationDir = new File(FMLPaths.CONFIGDIR.get().toFile(), ModIds.JEI_ID);
		if (!jeiConfigurationDir.exists()) {
			try {
				if (!jeiConfigurationDir.mkdir()) {
					throw new RuntimeException("Could not create config directory " + jeiConfigurationDir);
				}
			} catch (SecurityException e) {
				throw new RuntimeException("Could not create config directory " + jeiConfigurationDir, e);
			}
		}

		this.clientConfig = JEIClientConfig.clientConfig;
		this.ingredientFilterConfig = JEIClientConfig.filterConfig;
		this.modIdFormattingConfig = JEIClientConfig.modNameFormat;
		this.modIdHelper = new ForgeModIdHelper(clientConfig, modIdFormattingConfig);

		// Additional config files
		bookmarkConfig = new BookmarkConfig(jeiConfigurationDir);
		worldConfig = new WorldConfig(jeiConfigurationDir);
		editModeConfig = new EditModeConfig(jeiConfigurationDir);
		recipeCategorySortingConfig = new RecipeCategorySortingConfig(new File(jeiConfigurationDir, "recipe-category-sort-order.ini"));

		ModNameSortingConfig ingredientModNameSortingConfig = new ModNameSortingConfig(new File(jeiConfigurationDir, "ingredient-list-mod-sort-order.ini"));
		IngredientTypeSortingConfig ingredientTypeSortingConfig = new IngredientTypeSortingConfig(new File(jeiConfigurationDir, "ingredient-list-type-sort-order.ini"));
		ingredientSorter = new IngredientSorter(clientConfig, ingredientModNameSortingConfig, ingredientTypeSortingConfig);

		ErrorUtil.setModIdHelper(modIdHelper);
		ErrorUtil.setWorldConfig(worldConfig);

		KeyBindings.init();

		EventBusHelper.addListener(this, WorldEvent.Save.class, event -> worldConfig.onWorldSave());
		EventBusHelper.addListener(this, RecipesUpdatedEvent.class, event -> {
			ClientPlayNetHandler connection = Minecraft.getInstance().getConnection();
			if (connection != null) {
				NetworkManager networkManager = connection.getNetworkManager();
				worldConfig.syncWorldConfig(networkManager);
			}
			onRecipesLoaded();
			EventBusHelper.post(new PlayerJoinedWorldEvent());
		});

		networkHandler.createClientPacketHandler(worldConfig);

		this.textures = textures;
	}

	private void onRecipesLoaded() {
		modIdFormattingConfig.checkForModNameFormatOverride();

		List<IModPlugin> plugins = AnnotatedInstanceUtil.getModPlugins();

		// Reload when resources change
		Minecraft minecraft = Minecraft.getInstance();
		IResourceManager resourceManager = minecraft.getResourceManager();
		if (resourceManager instanceof IReloadableResourceManager) {
			IReloadableResourceManager reloadableResourceManager = (IReloadableResourceManager) resourceManager;
			reloadableResourceManager.addReloadListener(new JeiReloadListener(plugins));
		}
		if (minecraft.world != null) {
			Preconditions.checkNotNull(textures);
			this.starter.start(
				plugins,
				textures,
				clientConfig,
				editModeConfig,
				ingredientFilterConfig,
				worldConfig,
				bookmarkConfig,
				modIdHelper,
				recipeCategorySortingConfig,
				ingredientSorter
			);
		}
	}

	private final class JeiReloadListener implements ISelectiveResourceReloadListener {
		private final List<IModPlugin> plugins;

		private JeiReloadListener(List<IModPlugin> plugins) {
			this.plugins = plugins;
		}

		@Override
		public void onResourceManagerReload(IResourceManager resourceManager, Predicate<IResourceType> resourcePredicate) {
			// check that JEI has been started before. if not, do nothing
			if (starter.hasStarted() && Minecraft.getInstance().world != null) {
				LOGGER.info("Restarting JEI.");
				Preconditions.checkNotNull(textures);
				starter.start(
					plugins,
					textures,
					clientConfig,
					editModeConfig,
					ingredientFilterConfig,
					worldConfig,
					bookmarkConfig,
					modIdHelper,
					recipeCategorySortingConfig,
					ingredientSorter
				);
			}
		}
	}
}
