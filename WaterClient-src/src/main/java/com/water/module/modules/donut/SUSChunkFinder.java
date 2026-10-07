package com.water.module.modules.donut;

import com.water.module.Category;
import com.water.module.Module;
import com.water.module.setting.Setting;
import com.water.render.FontRenderer;
import com.water.render.ProjectedPoint;
import com.water.render.Render3D;
import com.water.render.RenderUtils;
import com.water.render.ShapeBatch;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.opengl.GL11;

public final class SUSChunkFinder extends Module {
   public static final double CHUNK_THICKNESS = 0.1;
   public static final double FLASH_THICKNESS = 0.3;
   public static final long FLASH_ON_MS = 150L;
   public static final long FLASH_CYCLE_MS = 400L;

   public final Setting<Integer> scanRadius = new Setting<>("Scan Radius", 1, 1, 5);
   public final Setting<Integer> clusterThreshold = new Setting<>("Sim Chunks", 10, 1, 10);
   public final Setting<Integer> baseThreshold = new Setting<>("Base Threshold", 15, 5, 40);
   public final Setting<Color> fillColor = new Setting<>("Fill Color", new Color(180, 60, 60, 40));
   public final Setting<Integer> fillAlpha = new Setting<>("Fill Alpha", 40, 0, 255);
   public final Setting<Boolean> showInfo = new Setting<>("Show % & Count", true);

   public final Set<ChunkPos> amethystHits = ConcurrentHashMap.newKeySet();
   public final Set<ChunkPos> baseHits = ConcurrentHashMap.newKeySet();
   public final Map<ChunkPos, Integer> amethystCounts = new ConcurrentHashMap<>();
   public volatile Set<ChunkPos> amethystRenderCache = Collections.emptySet();
   public volatile long flashAnchorMs = 0L;
   public ExecutorService scanExec;
   public final AtomicBoolean scanning = new AtomicBoolean(false);
   public int tickCount = 0;

   public static SUSChunkFinder INSTANCE;
   private final ProjectedPoint projected = new ProjectedPoint();

   public SUSChunkFinder() {
      super("SUS CHUNK FINDER", Category.DONUT);
      INSTANCE = this;
      this.addSetting(this.scanRadius);
      this.addSetting(this.clusterThreshold);
      this.addSetting(this.baseThreshold);
      this.addSetting(this.fillColor);
      this.addSetting(this.fillAlpha);
      this.addSetting(this.showInfo);
   }

   @Override
   public void onEnable() {
      this.clearScanState();
   }

   @Override
   public void onDisable() {
      this.clearScanState();
      if (this.scanExec != null) {
         this.scanExec.shutdownNow();
      }
   }

   public void clearScanState() {
      this.amethystHits.clear();
      this.baseHits.clear();
      this.amethystCounts.clear();
      this.amethystRenderCache = Collections.emptySet();
      this.tickCount = 0;
      this.scanning.set(false);
      this.flashAnchorMs = 0L;
   }

   @Override
   public void onTick() {
      if (mc.world == null || mc.player == null) return;

      ChunkPos chunkpos = mc.player.getChunkPos();
      int radius = this.scanRadius.getValue() * 5;

      this.amethystHits.removeIf(c -> Math.abs(c.x - chunkpos.x) > radius + 2 || Math.abs(c.z - chunkpos.z) > radius + 2);
      this.baseHits.removeIf(c -> Math.abs(c.x - chunkpos.x) > radius + 2 || Math.abs(c.z - chunkpos.z) > radius + 2);
      this.amethystCounts.entrySet().removeIf(e -> Math.abs(e.getKey().x - chunkpos.x) > radius + 2 || Math.abs(e.getKey().z - chunkpos.z) > radius + 2);

      if (++this.tickCount % 5 != 0) return;
      if (!this.scanning.compareAndSet(false, true)) return;

      if (this.scanExec == null || this.scanExec.isShutdown()) {
         this.scanExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "suschunk-scan");
            t.setDaemon(true);
            return t;
         });
      }ArrayList<ChunkPos> positions = new ArrayList<>();
      ArrayList<WorldChunk> chunks = new ArrayList<>();
      int cluster = this.clusterThreshold.getValue();

      for (int dx = -radius; dx <= radius; dx++) {
         for (int dz = -radius; dz <= radius; dz++) {
            ChunkPos cp = new ChunkPos(chunkpos.x + dx, chunkpos.z + dz);
            WorldChunk wc = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z, false);
            if (wc != null && !wc.isEmpty()) {
               positions.add(cp);
               chunks.add(wc);
            }
         }
      }

      this.scanExec.submit(() -> {
         try {
            HashMap<ChunkPos, Integer> amethystMap = new HashMap<>();
            HashSet<ChunkPos> baseSet = new HashSet<>();
            HashMap<ChunkPos, Integer> countMap = new HashMap<>();

            for (int i = 0; i < positions.size(); i++) {
               ChunkPos cp = positions.get(i);
               WorldChunk wc = chunks.get(i);
               int count = this.countAmethyst(wc);
               countMap.put(cp, count);
               if (count >= cluster) {
                  amethystMap.put(cp, count);
               }
               if (this.hasBaseSignature(wc)) {
                  baseSet.add(cp);
               }
            }

            Set<ChunkPos> top = this.pickTopChunks(amethystMap, chunkpos, cluster);
            Set<ChunkPos> filtered = this.applyShapeFilter(top, cluster);

            if (mc.player != null && mc.player.getY() <= -2.0) {
               filtered.removeIf(c -> !baseSet.contains(c));
            }

            this.amethystRenderCache = Collections.unmodifiableSet(filtered);
            this.amethystHits.clear();
            this.amethystHits.addAll(top);
            this.baseHits.clear();
            this.baseHits.addAll(baseSet);
            this.amethystCounts.clear();
            this.amethystCounts.putAll(countMap);
         } catch (Exception ignored) {
         } finally {
            this.scanning.set(false);
         }
      });
   }

   public Set<ChunkPos> pickTopChunks(Map<ChunkPos, Integer> map, ChunkPos origin, int limit) {
      ArrayList<Map.Entry<ChunkPos, Integer>> list = new ArrayList<>(map.entrySet());
      list.sort((a, b) -> {
         int cmp = Integer.compare(b.getValue(), a.getValue());
         if (cmp != 0) return cmp;
         return Integer.compare(chunkDistanceSq(a.getKey(), origin), chunkDistanceSq(b.getKey(), origin));
      });

      LinkedHashSet<ChunkPos> result = new LinkedHashSet<>();
      int max = Math.max(1, limit);
      for (Map.Entry<ChunkPos, Integer> e : list) {
         if (result.size() >= max) break;
         result.add(e.getKey());
      }
      return result;
   }

   public int chunkDistanceSq(ChunkPos a, ChunkPos b) {
      int dx = a.x - b.x;
      int dz = a.z - b.z;
      return dx * dx + dz * dz;
   }

   public Set<ChunkPos> applyShapeFilter(Set<ChunkPos> set, int threshold) {
      HashSet<ChunkPos> result = new HashSet<>();
      if (threshold >= 10) {
         result.addAll(set);
         return result;
      }
      int[][] shapes = {{1, 4}, {2, 4}, {1, 2}, {3, 1}, {5, 4}, {5, 6}};
      for (ChunkPos cp : set) {
         Random rnd = new Random(Math.abs(cp.hashCode()));
         int[] shape = shapes[rnd.nextInt(shapes.length)];
         int w = shape[0], h = shape[1];
         if (rnd.nextBoolean()) {
            int tmp = w; w = h; h = tmp;
         }
         int ox = -(w / 2), oz = -(h / 2);
         for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
               result.add(new ChunkPos(cp.x + ox + x, cp.z + oz + z));
            }
         }
      }
      return result;
   }

   public int countAmethyst(WorldChunk chunk) {
      int count = 0;
      ChunkSection[] sections = chunk.getSectionArray();
      int bottom = chunk.getBottomY();

      for (int s = 0; s < sections.length; s++) {
         int sectionY = bottom + s * 16;
         if (sectionY > 32) break;

         ChunkSection section = sections[s];
         if (section == null || section.isEmpty() || !section.hasAny(this::isAmethyst)) continue;

         for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
               for (int z = 0; z < 16; z++) {
                  if (this.isAmethyst(section.getBlockState(x, y, z))) {
                     count++;
                  }
               }
            }
         }
      }
      return count;
   }public boolean isAmethyst(BlockState state) {
      return state.isOf(Blocks.AMETHYST_CLUSTER) || state.isOf(Blocks.AMETHYST_BLOCK);
   }

   public boolean hasBaseSignature(WorldChunk chunk) {
      int storage = 0;
      boolean hasSpawner = false;

      ChunkSection[] sections = chunk.getSectionArray();
      int bottom = chunk.getBottomY();

      for (int s = 0; s < sections.length; s++) {
         int sectionY = bottom + s * 16;
         if (sectionY >= 0) break;

         ChunkSection section = sections[s];
         if (section == null || section.isEmpty()) continue;

         int maxY = Math.min(15, -sectionY - 1);
         for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
               for (int y = 0; y <= maxY; y++) {
                  BlockState state = section.getBlockState(x, y, z);
                  if (state.isOf(Blocks.SPAWNER)) {
                     hasSpawner = true;
                  }
                  if (isStorageBlock(state)) {
                     storage++;
                  }
               }
            }
         }
      }

      if (hasSpawner) return true;
      return storage >= this.baseThreshold.getValue();
   }

   private boolean isStorageBlock(BlockState state) {
      if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) return true;
      if (state.isOf(Blocks.BARREL) || state.isOf(Blocks.HOPPER)) return true;
      if (state.getBlock() instanceof ShulkerBoxBlock) return true;
      return false;
   }

   public int calcPercent(int amethystCount) {
      return (int) Math.min(100, Math.max(0, Math.round(amethystCount * 1.25)));
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world == null || mc.player == null) return;
      if (this.amethystRenderCache.isEmpty() && this.baseHits.isEmpty()) return;

      Camera camera = RenderUtils.getCamera();
      if (camera == null) return;

      Vec3d cam = RenderUtils.getCameraPos(camera);
      double boxY = 47.0 - cam.y;
      double boxY2 = boxY + 0.1;

      Color baseColor = this.fillColor.getValue();
      Color fill = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), this.fillAlpha.getValue());

      boolean flash = false;
      int flashAlpha = 0;
      if (!this.baseHits.isEmpty()) {
         long now = System.currentTimeMillis();
         if (this.flashAnchorMs == 0L) this.flashAnchorMs = now;
         long phase = (now - this.flashAnchorMs) % FLASH_CYCLE_MS;
         if (phase < FLASH_ON_MS) {
            flash = true;
            flashAlpha = (int) ((1.0F - (float) phase / FLASH_ON_MS) * 200.0F);
         }
      } else {
         this.flashAnchorMs = 0L;
      }

      matrices.push();
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      ShapeBatch batch = RenderUtils.beginShapeBatch(matrices);

      for (ChunkPos cp : this.amethystRenderCache) {
         double x1 = (cp.x << 4) - cam.x;
         double z1 = (cp.z << 4) - cam.z;
         batch.renderFilledBox(x1, boxY, z1, x1 + 16.0, boxY2, z1 + 16.0, fill);
      }

      if (flash && flashAlpha > 0) {
         Color white = new Color(255, 255, 255, flashAlpha);
         for (ChunkPos cp : this.baseHits) {
            double x1 = (cp.x << 4) - cam.x;
            double z1 = (cp.z << 4) - cam.z;
            batch.renderFilledBox(x1, boxY, z1, x1 + 16.0, boxY2 + 0.3, z1 + 16.0, white);
         }
      }

      batch.flush();
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      matrices.pop();
   }public static void renderHud(DrawContext context, float tickDelta) {
      SUSChunkFinder self = INSTANCE;
      if (self == null || !self.isEnabled() || !self.showInfo.getValue()) return;
      if (self.amethystRenderCache.isEmpty() && self.baseHits.isEmpty()) return;

      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc == null || mc.player == null || mc.world == null) return;

      double textY = 48.5;

      Set<ChunkPos> toDraw = new HashSet<>();
      toDraw.addAll(self.amethystRenderCache);
      toDraw.addAll(self.baseHits);

      for (ChunkPos cp : toDraw) {
         double worldX = (cp.x << 4) + 8.0;
         double worldZ = (cp.z << 4) + 8.0;

         if (!Render3D.projectToPoint(Render3D.modelViewMatrix, Render3D.projectionMatrix, worldX, textY, worldZ, self.projected)) {
            continue;
         }
         if (!self.projected.visible) continue;

         int count = self.amethystCounts.getOrDefault(cp, 0);
         int percent = self.calcPercent(count);

         String line1 = percent + "%";
         String line2 = String.valueOf(count);

         float x = (float) self.projected.x;
         float y = (float) self.projected.y;

         int w1 = FontRenderer.INSTANCE.getWidth(line1);
         int w2 = FontRenderer.INSTANCE.getWidth(line2);

         int color = percent >= 80 ? 0xFFFF5555 : (percent >= 50 ? 0xFFFFAA00 : 0xFFFFFFFF);

         FontRenderer.INSTANCE.drawString(context, line1, x - w1 / 2f + 1, y + 1, 0xFF000000);
         FontRenderer.INSTANCE.drawString(context, line1, x - w1 / 2f, y, color);

         FontRenderer.INSTANCE.drawString(context, line2, x - w2 / 2f + 1, y + 11, 0xFF000000);
         FontRenderer.INSTANCE.drawString(context, line2, x - w2 / 2f, y + 10, 0xFFFFFFFF);
      }
   }
}