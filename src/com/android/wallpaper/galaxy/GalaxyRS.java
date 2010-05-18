/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wallpaper.galaxy;

import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.ProgramRaster;
import android.renderscript.Allocation;
import android.renderscript.Sampler;
import android.renderscript.Element;
import android.renderscript.SimpleMesh;
import android.renderscript.Primitive;
import android.renderscript.Type;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendDstFunc;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.Element.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.TimeZone;

import com.android.wallpaper.R;
import com.android.wallpaper.RenderScriptScene;

class GalaxyRS extends RenderScriptScene {
    private static final int PARTICLES_COUNT = 12000;
    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;
    private ProgramVertex.MatrixAllocation mPvProjectionAlloc;
    private SimpleMesh mParticlesMesh;
    private ScriptC_galaxy mScript;

    GalaxyRS(int width, int height) {
        super(width, height);

        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    @Override
    protected ScriptC createScript() {
        mScript = new ScriptC_galaxy(mRS, mResources, R.raw.galaxy_bc);
        mScript.set_gIsPreview(isPreview() ? 1 : 0);
        if (isPreview()) {
            mScript.set_gXOffset(0.5f);
        }


        createParticlesMesh();
        createProgramVertex();
        createProgramRaster();
        createProgramFragmentStore();
        createProgramFragment();
        loadTextures();

        mScript.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mScript.setTimeZone(TimeZone.getDefault().getID());
        return mScript;
    }

    private void createParticlesMesh() {
        ScriptField_Particle p = new ScriptField_Particle(mRS, PARTICLES_COUNT);

        final SimpleMesh.Builder meshBuilder = new SimpleMesh.Builder(mRS);
        final int vertexSlot = meshBuilder.addVertexType(p.getType());
        meshBuilder.setPrimitive(Primitive.POINT);
        mParticlesMesh = meshBuilder.create();

        mScript.set_gParticlesMesh(mParticlesMesh);
        mScript.bind_Particles(p);
        mParticlesMesh.bindVertexAllocation(p.getAllocation(), 0);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScript.set_gXOffset(xOffset);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);
        mPvProjectionAlloc.setupProjectionNormalized(mWidth, mHeight);
    }

    private void loadTextures() {
        mScript.set_gTSpace(loadTexture(R.drawable.space));
        mScript.set_gTLight1(loadTexture(R.drawable.light1));
        mScript.set_gTFlares(loadTextureARGB(R.drawable.flares));
    }

    private Allocation loadTexture(int id) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565(mRS), false);
        allocation.uploadToTexture(0);
        return allocation;
    }

    // TODO: Fix Allocation.createFromBitmapResource() to do this when RGBA_8888 is specified
    private Allocation loadTextureARGB(int id) {
        Bitmap b = BitmapFactory.decodeResource(mResources, id, mOptionsARGB);
        final Allocation allocation = Allocation.createFromBitmap(mRS, b, RGBA_8888(mRS), false);
        allocation.uploadToTexture(0);
        return allocation;
    }

    private void createProgramFragment() {
        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS);
        builder.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                           ProgramFragment.Builder.Format.RGB, 0);
        ProgramFragment pfb = builder.create();
        pfb.bindSampler(Sampler.WRAP_NEAREST(mRS), 0);
        mScript.set_gPFBackground(pfb);

        builder = new ProgramFragment.Builder(mRS);
        builder.setPointSpriteTexCoordinateReplacement(true);
        builder.setTexture(ProgramFragment.Builder.EnvMode.MODULATE,
                           ProgramFragment.Builder.Format.RGBA, 0);
        ProgramFragment pfs = builder.create();
        pfs.bindSampler(Sampler.WRAP_LINEAR(mRS), 0);
        mScript.set_gPFStars(pfs);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
        mRS.contextBindProgramStore(builder.create());

        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE);
        mScript.set_gPSLights(builder.create());
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        ProgramVertex.Builder builder = new ProgramVertex.Builder(mRS, null, null);
        ProgramVertex pvbo = builder.create();
        pvbo.bindAllocation(mPvOrthoAlloc);
        mRS.contextBindProgramVertex(pvbo);

        mPvProjectionAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvProjectionAlloc.setupProjectionNormalized(mWidth, mHeight);

        builder = new ProgramVertex.Builder(mRS, null, null);
        ProgramVertex pvbp = builder.create();
        pvbp.bindAllocation(mPvProjectionAlloc);
        mScript.set_gPVBkProj(pvbp);

        ProgramVertex.ShaderBuilder sb = new ProgramVertex.ShaderBuilder(mRS);
        String t = "void main() {\n" +
                    "  float dist = ATTRIB_position.y;\n" +
                    "  float angle = ATTRIB_position.x;\n" +
                    "  float x = dist * sin(angle);\n" +
                    "  float y = dist * cos(angle) * 0.892;\n" +
                    "  float p = dist * 5.5;\n" +
                    "  float s = cos(p);\n" +
                    "  float t = sin(p);\n" +
                    "  vec4 pos;\n" +
                    "  pos.x = t * x + s * y;\n" +
                    "  pos.y = s * x - t * y;\n" +
                    "  pos.z = ATTRIB_position.z;\n" +
                    "  pos.w = 1.0;\n" +
                    "  gl_Position = UNI_MVP * pos;\n" +
                    "  gl_PointSize = ATTRIB_color.a * 10.0;\n" +
                    "  varColor.rgb = ATTRIB_color.rgb;\n" +
                    "  varColor.a = 1.0;\n" +
                    "}\n";
        sb.setShader(t);
        sb.addInput(mParticlesMesh.getVertexType(0).getElement());
        ProgramVertex pvs = sb.create();
        pvs.bindAllocation(mPvProjectionAlloc);
        mScript.set_gPVStars(pvs);
    }

    private void createProgramRaster() {
        ProgramRaster.Builder b = new ProgramRaster.Builder(mRS, null, null);
        b.setPointSmoothEnable(true);
        b.setPointSpriteEnable(true);
        ProgramRaster pr = b.create();
        mRS.contextBindProgramRaster(pr);
    }

}
