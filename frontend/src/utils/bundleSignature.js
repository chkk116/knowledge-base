/**
 * 热更包签名校验（RS256）。
 *
 * 背景：热更包是「能执行任意 JS」的载荷。只校验 SHA-256 挡不住能改元数据的人
 * （把 url/checksum 一起换掉即可）。这里用非对称签名：私钥只在发布机上，
 * 服务器只存签名，App 内置公钥——元数据被篡改则验签失败，直接拒绝更新。
 *
 * 签名原文固定为 `version\nurl\nchecksum`（与 publish-app-bundle.sh 保持一致）。
 * 公钥为 SPKI(PEM→DER) 的 base64，对应 outputs/.bundle-signing-key.pem。
 */

// 公钥随包内置；轮换密钥需要发新 APK
const PUBLIC_KEY_B64 =
  'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4IbJ0EWypJ79A93GSr/UGF5Uk6RIByEY8L1bj8wIwnxSS3HbZNmNNRofGFZAqSLKgGFl0q1t1RHfbUcVM1p4nUidIqrXSPRAfqcj8TQ5OqXWWhng3YUOMcI2Zu3Tuyu4q7yYScl0d5TWIwe/i5CNhsdQgf6XiLErvTAFKzbxQIuVaGkO7f1lyA9xNIU5hvWC+PpZUTglDZmn6/H5RS7b8fkgU5qvLfSMsnA+rthSuX1wr9wBJWFyYI63urbvs9NAgznzKJIhaBH1GWBnekLogAAopPDN2BffOusNudNQidCiFAE/nPKukMeAmiiDWWIiQxEbNUrQekJnQoClf+fiiwIDAQAB'

let keyPromise = null

function b64ToBytes(b64) {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function loadKey() {
  if (!keyPromise) {
    keyPromise = crypto.subtle.importKey(
      'spki',
      b64ToBytes(PUBLIC_KEY_B64),
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['verify']
    )
  }
  return keyPromise
}

/**
 * 校验热更包元数据签名。
 * @param {{version?:string,url?:string,checksum?:string,signature?:string}} meta
 * @returns {Promise<boolean>} 任一字段缺失或验签异常都返回 false（fail-closed）
 */
export async function verifyBundleSignature(meta) {
  if (!meta || !meta.signature || !meta.version || !meta.url || !meta.checksum) return false
  try {
    const key = await loadKey()
    const data = new TextEncoder().encode(`${meta.version}\n${meta.url}\n${meta.checksum}`)
    return await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, b64ToBytes(meta.signature), data)
  } catch (e) {
    console.warn('[热更] 签名校验异常', e)
    return false
  }
}
