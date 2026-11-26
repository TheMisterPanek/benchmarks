FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

COPY DotnetBlazingApp.csproj ./
RUN dotnet restore -r linux-x64

COPY Program.cs ./
RUN dotnet publish -c Release -r linux-x64 --no-self-contained -o /app --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app ./

ENV DOTNET_TieredPGO=1
ENV DOTNET_TC_QuickJitForLoops=1

EXPOSE 8080
ENTRYPOINT ["dotnet", "DotnetBlazingApp.dll"]
