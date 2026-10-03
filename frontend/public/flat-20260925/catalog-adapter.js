/** 将公开 Catalog 展开成旧播放器所需的模式条目；不自行补产品或启用模式。 */
export function expandCatalog(catalog) {
  if (!Array.isArray(catalog)) throw Error('产品目录格式不可用');
  return catalog.flatMap(p => (p.modes || []).map(policy => {
    const forecast = policy.dataMode === 'FORECAST';
    if (!forecast && policy.dataMode !== 'REALTIME') throw Error('未知产品模式');
    return {
      id: forecast ? 'FCST_' + p.code : p.code,
      pathId: p.code,
      name: p.nameZh,
      en: p.nameEn || '',
      unit: p.unit || '',
      mode: forecast ? 'forecast' : 'realtime',
      staleAfterMinutes: policy.staleAfterMinutes,
      colorbarRequired: p.colorbarRequired,
      combined: forecast && p.code === 'PRECIP'
    };
  }));
}

/** 查询所选产品的公开色标地址；失败交给页面显示，不猜测服务器文件名。 */
export async function loadColorbar(code, fetcher = fetch) {
  const response = await fetcher('/api/v1/products/' + encodeURIComponent(code));
  if (!response.ok) throw Error('产品色标配置读取失败');
  const detail = await response.json();
  const url = detail.colorbarUrl;
  if (url == null) return null;
  if (typeof url !== 'string' || !url.startsWith('/') || url.startsWith('//') || /[\\\r\n]/.test(url)) {
    throw Error('产品色标地址不可用');
  }
  return url;
}
