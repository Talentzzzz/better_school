import { httpPost } from './http'

export function uploadImage(file: File) {
  const form = new FormData()
  form.append('file', file)
  return httpPost<{ url: string }>('/files/upload', form).then((res) => res.url)
}
