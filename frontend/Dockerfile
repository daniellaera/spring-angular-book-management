FROM node:24-slim AS build

WORKDIR /app

COPY package*.json ./

RUN npm ci

COPY . .

# The PrimeUI licence key comes in as a BuildKit secret (docker build --secret id=primeui_license,env=PRIMEUI_LICENSE):
# it is mounted only for this RUN, never an ARG/ENV, so it is not in the image history or any layer of this
# stage. `npm run build` runs the prebuild hook, which fails the build if the key is empty.
RUN --mount=type=secret,id=primeui_license \
    PRIMEUI_LICENSE="$(cat /run/secrets/primeui_license)" npm run build -- --configuration=production

FROM nginx:alpine

COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html

COPY ./config/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
