/* =====================================================================
   Warp text -- the landing headline, rendered through WebGL glass.

   Ported from the React Bits <WarpText /> component. The fragment
   shader below is the original, essentially unchanged: it is the part
   that does the work. Everything around it has been rewritten.

   WHY IT WAS PORTED RATHER THAN INSTALLED
   ---------------------------------------
   The original ships as React + JSX and depends on ogl. This project
   has no React, no npm and no build step -- it is static HTML served by
   Tomcat beside a CGI script, and its report leans on having no
   external dependencies. Adding a bundler, a component framework and a
   WebGL wrapper library for one headline would cost more than the
   headline is worth.

   The React half of that component is lifecycle plumbing: mount,
   resize, pointer, teardown. That is what has been rewritten here in
   plain JS. ogl's Renderer/Program/Mesh/Texture are thin covers over
   WebGL2 calls, so those are made directly. Net dependencies: none.

   PROGRESSIVE ENHANCEMENT
   -----------------------
   The real <h1> stays in the DOM and keeps its layout. This script only
   makes it transparent once the canvas is genuinely drawing. If WebGL2
   is missing, the context is lost, or this file never loads, the page
   is exactly what it was before: readable text in Playfair Display.
   A landing page whose only content is its title must never be able to
   render blank.
   ===================================================================== */

(function () {
  "use strict";

  var VERT =
    "#version 300 es\n" +
    "in vec2 position;\n" +
    "in vec2 uv;\n" +
    "out vec2 vUv;\n" +
    "void main() {\n" +
    "  vUv = uv;\n" +
    "  gl_Position = vec4(position, 0.0, 1.0);\n" +
    "}\n";

  /* The original fragment shader, unchanged apart from formatting. */
  var FRAG =
    "#version 300 es\n" +
    "precision highp float;\n" +
    "uniform sampler2D uTextTexture;\n" +
    "uniform vec2 uResolution;\n" +
    "uniform vec2 uPointer;\n" +
    "uniform float uPointerActive;\n" +
    "uniform float uTime;\n" +
    "uniform float uWarpStrength;\n" +
    "uniform float uWarpScale;\n" +
    "uniform float uSpeed;\n" +
    "uniform float uPointerInfluence;\n" +
    "uniform float uPointerStrength;\n" +
    "uniform float uRefraction;\n" +
    "uniform float uRipple;\n" +
    "uniform float uMotion;\n" +
    "in vec2 vUv;\n" +
    "out vec4 fragColor;\n" +
    "float hash(vec2 p) {\n" +
    "  p = fract(p * vec2(123.34, 456.21));\n" +
    "  p += dot(p, p + 45.32);\n" +
    "  return fract(p.x * p.y);\n" +
    "}\n" +
    "float noise(vec2 p) {\n" +
    "  vec2 i = floor(p);\n" +
    "  vec2 f = fract(p);\n" +
    "  vec2 u = f * f * (3.0 - 2.0 * f);\n" +
    "  float a = hash(i);\n" +
    "  float b = hash(i + vec2(1.0, 0.0));\n" +
    "  float c = hash(i + vec2(0.0, 1.0));\n" +
    "  float d = hash(i + vec2(1.0, 1.0));\n" +
    "  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);\n" +
    "}\n" +
    "float fbm(vec2 p) {\n" +
    "  float value = 0.0;\n" +
    "  float amplitude = 0.5;\n" +
    "  for (int i = 0; i < 4; i++) {\n" +
    "    value += amplitude * noise(p);\n" +
    "    p *= 2.02;\n" +
    "    amplitude *= 0.5;\n" +
    "  }\n" +
    "  return value;\n" +
    "}\n" +
    "vec4 sampleText(vec2 uv) {\n" +
    "  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n" +
    "    return vec4(0.0);\n" +
    "  }\n" +
    "  return texture(uTextTexture, uv);\n" +
    "}\n" +
    "void main() {\n" +
    "  vec2 uv = vUv;\n" +
    "  float aspect = uResolution.x / max(uResolution.y, 1.0);\n" +
    "  float time = uTime * uSpeed;\n" +
    "  float scale = max(uWarpScale, 0.001);\n" +
    "  vec2 drift = vec2(time * 0.055, -time * 0.045);\n" +
    "  float n1 = fbm(uv * scale * 3.1 + drift);\n" +
    "  float n2 = fbm((uv + 19.17) * scale * 3.4 - drift.yx);\n" +
    "  vec2 ambient = (vec2(n1, n2) - 0.5) * uWarpStrength * 0.045 * uMotion;\n" +
    "  vec2 pointerDelta = uv - uPointer;\n" +
    "  vec2 aspectDelta = vec2(pointerDelta.x * aspect, pointerDelta.y);\n" +
    "  float dist = length(aspectDelta);\n" +
    "  float radius = max(uPointerInfluence, 0.001);\n" +
    "  float t = clamp(dist / radius, 0.0, 1.0);\n" +
    "  float lens = smoothstep(radius, 0.0, dist) * uPointerActive;\n" +
    "  float bulge = t * (1.0 - t) * (1.0 - t) * 6.75 * uPointerActive;\n" +
    "  vec2 dir = dist > 0.0001 ? vec2(aspectDelta.x / aspect, aspectDelta.y) / dist : vec2(0.0);\n" +
    "  float rippleWave = sin(dist * 28.0 - time * 4.2) * 0.5 + 0.5;\n" +
    "  float rippleRing = (rippleWave - 0.5) * uRipple;\n" +
    "  vec2 pointerWarp = -dir * bulge * uPointerStrength * 0.045;\n" +
    "  pointerWarp += dir * rippleRing * bulge * uPointerStrength * 0.016;\n" +
    "  vec2 displaced = uv + ambient + pointerWarp;\n" +
    "  vec2 splitDir = ambient + pointerWarp;\n" +
    "  float splitLen = length(splitDir);\n" +
    "  splitDir = splitLen > 0.00001 ? splitDir / splitLen : vec2(0.7071, 0.7071);\n" +
    "  vec2 split = splitDir * uRefraction * 0.16 * (0.35 + lens * 1.65);\n" +
    "  vec4 base = sampleText(displaced);\n" +
    "  float r = sampleText(displaced + split).r;\n" +
    "  float g = base.g;\n" +
    "  float b = sampleText(displaced - split).b;\n" +
    "  float a = max(max(sampleText(displaced + split).a, base.a), sampleText(displaced - split).a);\n" +
    "  vec3 color = vec3(r, g, b) + lens * base.a * 0.055;\n" +
    "  fragColor = vec4(color, a);\n" +
    "}\n";

  /* -------------------------------------------------------------------
     Defaults, tuned for this project rather than taken as shipped.

     refraction is 0. It splits the red and blue channels to fake a
     glass edge, which paints colour fringes onto the letters -- and
     this portal is deliberately two colours, cream and black, with no
     third hue anywhere. Set it to about 0.015 if you decide the glass
     look is worth breaking that rule for.

     warpStrength is below the original 0.08: a thin italic serif at
     this size smears long before a heavy sans would.
     ------------------------------------------------------------------- */
  var DEFAULTS = {
    warpStrength: 0.055,
    warpScale: 1.7,
    speed: 0.5,
    pointerInfluence: 0.42,
    pointerStrength: 0.38,
    refraction: 0,
    ripple: true
  };

  function compile(gl, type, source) {
    var shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      var log = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      throw new Error("shader compile failed: " + log);
    }
    return shader;
  }

  function link(gl, vs, fs) {
    var program = gl.createProgram();
    gl.attachShader(program, vs);
    gl.attachShader(program, fs);
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      var log = gl.getProgramInfoLog(program);
      gl.deleteProgram(program);
      throw new Error("program link failed: " + log);
    }
    return program;
  }

  /* The lines of the heading, taken from the element itself so the
     canvas and the DOM can never disagree about the wording. */
  function linesOf(el) {
    var decoder = document.createElement("div");
    return el.innerHTML.split(/<br\s*\/?>/i).map(function (part) {
      decoder.innerHTML = part;
      return (decoder.textContent || "").trim();
    }).filter(function (line) { return line.length > 0; });
  }

  function measure(ctx, line, tracking) {
    var chars = Array.from(line);
    var w = 0;
    for (var i = 0; i < chars.length; i++) w += ctx.measureText(chars[i]).width;
    return w + Math.max(0, chars.length - 1) * tracking;
  }

  /* Drawn character by character because canvas 2D has no
     letter-spacing everywhere yet, and this heading is tracked in. */
  function drawLine(ctx, line, left, y, tracking) {
    var chars = Array.from(line);
    var cursor = left;
    for (var i = 0; i < chars.length; i++) {
      ctx.fillText(chars[i], cursor, y);
      cursor += ctx.measureText(chars[i]).width + (i === chars.length - 1 ? 0 : tracking);
    }
  }

  /*
    Rasterises the heading into a 2D canvas, reading the font straight
    off the live element. That is the one real departure from the
    original, which took font settings as props: here the DOM already
    knows the answer -- including whatever the CSS clamp() resolved to
    at this viewport -- so asking it keeps the warped text identical in
    size and position to the text it replaces.
  */
  /*
    Rasterises every [data-warp] element into one canvas, each drawn at
    its own position with its own font, measured against the container
    the WebGL canvas covers.

    One texture rather than one per element: the shader warps a single
    image, so the headline, the cue and the footer links all bend
    through the same piece of glass and stay visually of a piece.

    Fonts and geometry are read live from the DOM, which means whatever
    the CSS clamp() resolved to at this viewport is what gets drawn. Ink
    colours are passed in, captured before any element was made
    transparent -- see the note in init().
  */
  function rasteriseAll(targets, root, width, height, dpr, inks) {
    var canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.floor(width * dpr));
    canvas.height = Math.max(1, Math.floor(height * dpr));

    var ctx = canvas.getContext("2d");
    if (!ctx) return null;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);
    ctx.textAlign = "left";
    ctx.textBaseline = "middle";
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";

    var frame = root.getBoundingClientRect();
    var drewSomething = false;

    for (var t = 0; t < targets.length; t++) {
      var el = targets[t];
      var lines = linesOf(el);
      if (!lines.length) continue;

      var box = el.getBoundingClientRect();
      if (box.width <= 0 || box.height <= 0) continue;

      var cs = window.getComputedStyle(el);
      var fontSize = parseFloat(cs.fontSize) || 16;
      var tracking = cs.letterSpacing === "normal"
        ? 0 : (parseFloat(cs.letterSpacing) || 0);
      var leading = parseFloat(cs.lineHeight);
      if (!isFinite(leading)) leading = fontSize * 1.2;

      ctx.font = (cs.fontStyle || "normal") + " " + (cs.fontWeight || "400")
               + " " + fontSize + "px " + (cs.fontFamily || "serif");
      ctx.fillStyle = inks[t] || "#000";

      // Position is measured, never assumed. The landing inset comes
      // from padding on the container rather than on these elements, so
      // centring in the canvas -- as the original component did -- puts
      // the first line off the left edge. Measuring both boxes is
      // immune to wherever the spacing happens to be declared.
      var left = box.left - frame.left;
      var top = box.top - frame.top;

      for (var i = 0; i < lines.length; i++) {
        // A single line is centred in its own box, which is reliable
        // whatever the line-height. Several lines step by the leading.
        var y = (lines.length === 1)
          ? top + box.height / 2
          : top + leading * (i + 0.5);
        drawLine(ctx, lines[i], left, y, tracking);
      }
      drewSomething = true;
    }

    return drewSomething ? canvas : null;
  }

  function init(root) {
    var targets = Array.prototype.slice.call(root.querySelectorAll("[data-warp]"));
    if (!targets.length) return;

    // Ink colours are captured once, now, while every element still has
    // its own. rasteriseAll() draws the very elements that upload()
    // then makes transparent, so reading colour at draw time would make
    // the second pass -- a resize, or fonts.ready firing -- paint the
    // text in transparent ink and blank the canvas.
    var inks = targets.map(function (el) {
      return window.getComputedStyle(el).color || "#000";
    });

    var canvas = document.createElement("canvas");
    var gl = null;
    try {
      gl = canvas.getContext("webgl2", {
        alpha: true,
        premultipliedAlpha: false,
        antialias: true,
        depth: false,
        stencil: false
      });
    } catch (e) {
      gl = null;
    }
    // No WebGL2: leave the page exactly as it is. The heading is
    // already on screen and readable.
    if (!gl) return;

    var program;
    try {
      program = link(gl, compile(gl, gl.VERTEX_SHADER, VERT),
                         compile(gl, gl.FRAGMENT_SHADER, FRAG));
    } catch (e) {
      if (window.console) console.warn("warp-text:", e.message);
      return;
    }

    canvas.className = "warp-canvas";
    canvas.setAttribute("aria-hidden", "true");
    root.appendChild(canvas);

    // NOTE: the heading is NOT made transparent here. That happens in
    // upload(), once a texture has actually been handed to the GPU and
    // drawn. Hiding the text up front means any container that has no
    // size yet -- a hidden tab, a display:none ancestor, a viewport
    // still reporting 0 -- leaves a completely blank page, because the
    // canvas never gets sized and nothing replaces what was hidden.
    // Prove the replacement works first, then remove the original.

    gl.useProgram(program);

    // One triangle large enough to cover the viewport, which avoids a
    // seam down the diagonal of a two-triangle quad.
    var vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    var posBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, posBuf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    var posLoc = gl.getAttribLocation(program, "position");
    gl.enableVertexAttribArray(posLoc);
    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);

    var uvBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, uvBuf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 2, 0, 0, 2]), gl.STATIC_DRAW);
    var uvLoc = gl.getAttribLocation(program, "uv");
    gl.enableVertexAttribArray(uvLoc);
    gl.vertexAttribPointer(uvLoc, 2, gl.FLOAT, false, 0, 0);

    var texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

    var U = {};
    ["uTextTexture", "uResolution", "uPointer", "uPointerActive", "uTime",
     "uWarpStrength", "uWarpScale", "uSpeed", "uPointerInfluence",
     "uPointerStrength", "uRefraction", "uRipple", "uMotion"
    ].forEach(function (name) { U[name] = gl.getUniformLocation(program, name); });

    gl.uniform1i(U.uTextTexture, 0);
    gl.uniform1f(U.uWarpStrength, DEFAULTS.warpStrength);
    gl.uniform1f(U.uWarpScale, DEFAULTS.warpScale);
    gl.uniform1f(U.uSpeed, DEFAULTS.speed);
    gl.uniform1f(U.uPointerInfluence, DEFAULTS.pointerInfluence);
    gl.uniform1f(U.uPointerStrength, DEFAULTS.pointerStrength);
    gl.uniform1f(U.uRefraction, DEFAULTS.refraction);
    gl.uniform1f(U.uRipple, DEFAULTS.ripple ? 1 : 0);

    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.clearColor(0, 0, 0, 0);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);

    var motionQuery = window.matchMedia ?
      window.matchMedia("(prefers-reduced-motion: reduce)") : null;
    var reduceMotion = motionQuery ? motionQuery.matches : false;
    gl.uniform1f(U.uMotion, reduceMotion ? 0 : 1);

    var pointer = { x: 0.5, y: 0.5, tx: 0.5, ty: 0.5, active: 0, target: 0 };
    var start = performance.now();
    var raf = 0;
    var disposed = false;
    var lost = false;
    var onScreen = true;
    var pageVisible = !document.hidden;

    function draw() {
      if (disposed || lost) return;
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.bindVertexArray(vao);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }

    function upload() {
      var rect = root.getBoundingClientRect();
      // No size yet. Leave the real heading showing; the
      // ResizeObserver calls back the moment the box gains dimensions.
      if (rect.width <= 0 || rect.height <= 0) return;
      var dpr = Math.min(window.devicePixelRatio || 1, 2);
      var img = rasteriseAll(targets, root, rect.width, rect.height, dpr, inks);
      if (!img) return;
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
      draw();

      // The canvas is now carrying the text, so the DOM copies can step
      // back. They keep their boxes, their links and their place in the
      // accessibility tree -- only their ink goes.
      for (var i = 0; i < targets.length; i++) {
        targets[i].classList.add("is-warped");
      }
    }

    function resize() {
      if (disposed || lost) return;
      var rect = root.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      var dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.floor(rect.width * dpr);
      canvas.height = Math.floor(rect.height * dpr);
      canvas.style.width = rect.width + "px";
      canvas.style.height = rect.height + "px";
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.uniform2f(U.uResolution, canvas.width, canvas.height);
      upload();
    }

    function loop(now) {
      if (disposed || lost) return;
      var elapsed = (now - start) * 0.001;

      // With no pointer the lens drifts on its own, so the headline is
      // alive before anyone touches it.
      var idleX = 0.5 + Math.sin(elapsed * 0.33) * 0.12;
      var idleY = 0.5 + Math.cos(elapsed * 0.27) * 0.10;
      var targetX = pointer.target > 0 ? pointer.tx : idleX;
      var targetY = pointer.target > 0 ? pointer.ty : idleY;
      var damping = pointer.target > 0 ? 0.12 : 0.035;

      pointer.x += (targetX - pointer.x) * damping;
      pointer.y += (targetY - pointer.y) * damping;
      pointer.active += ((pointer.target > 0 ? 1 : 0.18) - pointer.active) * 0.06;

      gl.uniform2f(U.uPointer, pointer.x, pointer.y);
      gl.uniform1f(U.uPointerActive, reduceMotion ? pointer.active * 0.35 : pointer.active);
      gl.uniform1f(U.uTime, reduceMotion ? 0 : elapsed);

      draw();
      raf = requestAnimationFrame(loop);
    }

    function play() {
      if (!raf && !disposed && !lost && onScreen && pageVisible) {
        raf = requestAnimationFrame(loop);
      }
    }
    function pause() {
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
    }

    // Pointer events sit on the root, not the canvas, so the lens still
    // follows the cursor over the cue text and the whitespace around
    // the heading.
    root.addEventListener("pointermove", function (e) {
      if (e.pointerType === "touch") return;
      var rect = canvas.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      pointer.tx = (e.clientX - rect.left) / rect.width;
      pointer.ty = 1 - (e.clientY - rect.top) / rect.height;
      pointer.target = 1;
    });
    root.addEventListener("pointerleave", function () { pointer.target = 0; });

    canvas.addEventListener("webglcontextlost", function (e) {
      // Without preventDefault the context can never be restored; and
      // uncovering the real heading means the page stays readable.
      e.preventDefault();
      lost = true;
      pause();
      for (var i = 0; i < targets.length; i++) {
        targets[i].classList.remove("is-warped");
      }
      canvas.style.display = "none";
    }, false);

    document.addEventListener("visibilitychange", function () {
      pageVisible = !document.hidden;
      if (pageVisible) play(); else pause();
    });

    if (motionQuery && motionQuery.addEventListener) {
      motionQuery.addEventListener("change", function (e) {
        reduceMotion = e.matches;
        gl.uniform1f(U.uMotion, reduceMotion ? 0 : 1);
        draw();
      });
    }

    if (window.ResizeObserver) {
      new ResizeObserver(resize).observe(root);
    } else {
      window.addEventListener("resize", resize);
    }

    if (window.IntersectionObserver) {
      new IntersectionObserver(function (entries) {
        onScreen = entries[0].isIntersecting;
        if (onScreen) play(); else pause();
      }, { threshold: 0 }).observe(root);
    }

    // Webfonts land after first paint, and rasterising before Playfair
    // arrives would bake the fallback serif into the texture.
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(upload).catch(function () {});
    }

    resize();
    play();
  }

  function boot() {
    // The canvas covers the whole landing page, not just the <main>:
    // the footer links live outside it and are warped too.
    var root = document.querySelector(".landing-body")
            || document.getElementById("landing");
    if (root) init(root);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
