/** Reduce 2D interaction work, restoring full display quality when the camera settles. */
export function configureMapInteraction(viewer){
  const fullScale=Math.min(window.devicePixelRatio||1,1.5);
  let moving=false,boundaries;
  function updateQuality(){
    const interacting=moving&&viewer.scene.mode===Cesium.SceneMode.SCENE2D;
    const scale=interacting?Math.min(1,fullScale*.8):fullScale;
    if(viewer.resolutionScale!==scale)viewer.resolutionScale=scale;
    document.body.classList.toggle('map-interacting-2d',interacting);
    viewer.scene.requestRender();
  }
  function updateBoundaries(){
    const mode=viewer.scene.mode;
    if(!boundaries||mode===Cesium.SceneMode.MORPHING)return;
    // In a flat map over the ellipsoid, ordinary polylines retain coordinates without ground classification.
    const clamp=mode!==Cesium.SceneMode.SCENE2D;
    for(const entity of boundaries.entities.values){
      if(entity.polyline)entity.polyline.clampToGround=clamp;
    }
    viewer.scene.requestRender();
  }
  viewer.camera.moveStart.addEventListener(()=>{moving=true;updateQuality();});
  viewer.camera.moveEnd.addEventListener(()=>{moving=false;updateQuality();});
  viewer.scene.morphStart.addEventListener(()=>{moving=false;updateQuality();});
  viewer.scene.morphComplete.addEventListener(()=>{moving=false;updateBoundaries();updateQuality();});
  updateQuality();
  // Called after asynchronous GeoJSON loading; the current mode may differ from the startup mode.
  return dataSource=>{boundaries=dataSource;updateBoundaries();};
}
