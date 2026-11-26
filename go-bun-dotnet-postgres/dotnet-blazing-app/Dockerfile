ARG DOTNET_CHANNEL=10.0
FROM mcr.microsoft.com/dotnet/sdk:${DOTNET_CHANNEL} AS build
ARG DOTNET_CHANNEL=10.0
ENV TargetFramework=net${DOTNET_CHANNEL}
WORKDIR /src

COPY DotnetBlazingApp.csproj ./
RUN dotnet restore -r linux-x64

COPY Program.cs ./
RUN dotnet publish -c Release -r linux-x64 --no-self-contained -o /app --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:${DOTNET_CHANNEL}
WORKDIR /app
COPY --from=build /app ./

ENV DOTNET_TieredPGO=1
ENV DOTNET_TC_QuickJitForLoops=1

EXPOSE 8080
ENTRYPOINT ["dotnet", "DotnetBlazingApp.dll"]
