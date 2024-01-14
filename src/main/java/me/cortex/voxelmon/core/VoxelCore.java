package me.cortex.voxelmon.core;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxelmon.core.rendering.*;
import me.cortex.voxelmon.core.rendering.building.BuiltSectionGeometry;
import me.cortex.voxelmon.core.rendering.building.RenderGenerationService;
import me.cortex.voxelmon.core.util.DebugUtil;
import me.cortex.voxelmon.core.util.MemoryBuffer;
import me.cortex.voxelmon.core.util.RingUtil;
import me.cortex.voxelmon.core.world.WorldEngine;
import me.cortex.voxelmon.core.world.WorldSection;
import me.cortex.voxelmon.core.world.other.BiomeColour;
import me.cortex.voxelmon.core.world.other.BlockStateColour;
import me.cortex.voxelmon.core.world.other.ColourResolver;
import me.cortex.voxelmon.core.world.other.Mapper;
import me.cortex.voxelmon.importers.WorldImporter;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.util.*;

import static org.lwjgl.opengl.ARBFramebufferObject.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.ARBFramebufferObject.glBindFramebuffer;

//Core class that ingests new data from sources and updates the required systems

//3 primary services:
// ingest service: this takes in unloaded chunk events from the client, processes the chunk and critically also updates the lod view of the world
// render data builder service: this service builds the render data from build requests it also handles the collecting of build data for the selected region (only axis aligned single lod tasks)
// serialization service: serializes changed world data and ensures that the database and any loaded data are in sync such that the database can never be more updated than loaded data, also performs compression on serialization

//there are multiple subsystems
//player tracker system (determines what lods are loaded and used by the player)
//updating system (triggers render data rebuilds when something from the ingest service causes an LOD change)
//the render system simply renders what data it has, its responsable for gpu memory layouts in arenas and rendering in an optimal way, it makes no requests back to any of the other systems or services, it just applies render data updates

//There is strict forward only dataflow
//Ingest -> world engine -> raw render data -> render data
public class VoxelCore {
    private static final Set<Block> biomeTintableAllFaces = new HashSet<>(List.of(Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.VINE, Blocks.MANGROVE_LEAVES,
            Blocks.TALL_GRASS, Blocks.LARGE_FERN, Blocks.SHORT_GRASS,

            Blocks.SPRUCE_LEAVES,
            Blocks.BIRCH_LEAVES,
            Blocks.PINK_PETALS,
            Blocks.FERN, Blocks.POTTED_FERN));
    private static final Set<Block> biomeTintableUpFace = new HashSet<>(List.of(Blocks.GRASS_BLOCK));
    private static final Set<Block> waterTint = new HashSet<>(List.of(Blocks.WATER));


    private final WorldEngine world;
    private final DistanceTracker distanceTracker;
    private final RenderGenerationService renderGen;
    private final RenderTracker renderTracker;

    private final AbstractFarWorldRenderer renderer;
    private final PostProcessing postProcessing;

    //private final Thread shutdownThread = new Thread(this::shutdown);

    public VoxelCore() {
        //Trigger the shared index buffer loading
        SharedIndexBuffer.INSTANCE.id();
        this.renderer = new Gl46FarWorldRenderer();
        this.world = new WorldEngine(new File("storagefile2.db"), 2, 5, 5);//"storagefile.db"//"ethoslab.db"

        this.renderTracker = new RenderTracker(this.world, this.renderer);
        this.renderGen = new RenderGenerationService(this.world,5, this.renderTracker::processBuildResult);
        this.world.setRenderTracker(this.renderTracker);
        this.renderTracker.setRenderGen(this.renderGen);

        //To get to chunk scale multiply the scale by 2, the scale is after how many chunks does the lods halve
        this.distanceTracker = new DistanceTracker(this.renderTracker, 5, 16);//20

        this.postProcessing = new PostProcessing();

        this.world.getMapper().setCallbacks(this::stateUpdate, this::biomeUpdate);

        //Runtime.getRuntime().addShutdownHook(this.shutdownThread);


        //WorldImporter importer = new WorldImporter(this.world, MinecraftClient.getInstance().world);
        //importer.importWorldAsyncStart(new File("saves/New World/region"));



        for (var state : this.world.getMapper().getStateEntries()) {
            this.stateUpdate(state);
        }

        for (var biome : this.world.getMapper().getBiomeEntries()) {
            this.biomeUpdate(biome);
        }
    }

    private void stateUpdate(Mapper.StateEntry entry) {
        var state = entry.state;
        int tintMsk = 0;
        if (biomeTintableAllFaces.contains(state.getBlock())) {
            tintMsk |= (1<<6)-1;
        }
        if (biomeTintableUpFace.contains(state.getBlock())) {
            tintMsk |= 1<<Direction.UP.getId();
        }
        if (waterTint.contains(state.getBlock())) {
            tintMsk |= 1<<6;
        }
        this.renderer.enqueueUpdate(new BlockStateColour(entry.id, tintMsk, ColourResolver.resolveColour(state)));
    }

    private void biomeUpdate(Mapper.BiomeEntry entry) {
        long dualColour = ColourResolver.resolveBiomeColour(entry.biome);
        this.renderer.enqueueUpdate(new BiomeColour(entry.id, (int) dualColour, (int) (dualColour>>32)));
    }


    public void enqueueIngest(WorldChunk worldChunk) {
        this.world.ingestService.enqueueIngest(worldChunk);
    }

    boolean firstTime = true;
    public void renderSetup(Frustum frustum, Camera camera) {
        if (this.firstTime) {
            this.distanceTracker.init(camera.getBlockPos().getX(), camera.getBlockPos().getZ());
            this.firstTime = false;
        }
        this.distanceTracker.setCenter(camera.getBlockPos().getX(), camera.getBlockPos().getY(), camera.getBlockPos().getZ());
        this.renderer.setupRender(frustum, camera);
    }

    public void renderOpaque(MatrixStack matrices, double cameraX, double cameraY, double cameraZ) {
        matrices.push();
        matrices.translate(-cameraX, -cameraY, -cameraZ);
        DebugUtil.setPositionMatrix(matrices);
        matrices.pop();

        //int boundFB = GlStateManager.getBoundFramebuffer();
        //this.postProcessing.setSize(MinecraftClient.getInstance().getFramebuffer().textureWidth, MinecraftClient.getInstance().getFramebuffer().textureHeight);
        //this.postProcessing.bindClearFramebuffer();

        //TODO: FIXME: since we just bound the post processing FB the depth information isnt
        // copied over, we must do this manually and also copy it with respect to the
        // near/far planes


        //TODO: have the renderer also render a bounding full face just like black boarders around lvl 0
        // this is cause the terrain might not exist and so all the caves are visible causing hell for the
        // occlusion culler
        this.renderer.renderFarAwayOpaque(matrices, cameraX, cameraY, cameraZ);


        //glBindFramebuffer(GL_FRAMEBUFFER, boundFB);
        //this.postProcessing.renderPost(boundFB);
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("");
        debug.add("");
        debug.add("VoxelCore");
        debug.add("Ingest service tasks: " + this.world.ingestService.getTaskCount());
        debug.add("Saving service tasks: " + this.world.savingService.getTaskCount());
        debug.add("Render service tasks: " + this.renderGen.getTaskCount());
        debug.add("Loaded cache sizes: " + Arrays.toString(this.world.getLoadedSectionCacheSizes()));
        this.renderer.addDebugData(debug);
    }

    //Note: when doing translucent rendering, only need to sort when generating the geometry, or when crossing into the center zone
    // cause in 99.99% of cases the sections dont need to be sorted
    // since they are AABBS crossing the normal is impossible without one of the axis being equal

    public void shutdown() {
        //if (Thread.currentThread() != this.shutdownThread) {
        //    Runtime.getRuntime().removeShutdownHook(this.shutdownThread);
        //}

        //this.world.getMapper().forceResaveStates();
        System.out.println("Shutting down voxel core");
        try {this.renderGen.shutdown();} catch (Exception e) {System.err.println(e);}
        try {this.world.shutdown();} catch (Exception e) {System.err.println(e);}
        try {this.renderer.shutdown();} catch (Exception e) {System.err.println(e);}
        try {this.postProcessing.shutdown();} catch (Exception e) {System.err.println(e);}
    }

    public WorldImporter createWorldImporter(World mcWorld, File worldPath) {
        var importer = new WorldImporter(this.world, mcWorld);
        importer.importWorldAsyncStart(worldPath, 5, null, ()->{
            System.err.println("DONE IMPORT");
        });
        return importer;
    }
}
