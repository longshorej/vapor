const HtmlWebPackPlugin = require("html-webpack-plugin");
const resolve = require('path').resolve;
const Webpack = require('webpack');

module.exports = {
  devServer: {
    proxy: {
      '/api': 'http://localhost:13542'
    }
  },
  module: {
    rules: [
      {
        test: /\.js$/,
        exclude: /node_modules/,
        use: {
          loader: "babel-loader"
        }
      },
      {
        test: /\.css$/,
        use: ["style-loader", "css-loader"]
      },
      {
          test: /\.scss$/,
          use: [
            "style-loader",
            "css-loader",
            "sass-loader"
          ]
      },
      {
        test: /\.(woff|woff2)$/,
        use: {
          loader: 'url-loader',
          options: {
            name: 'fonts/[hash].[ext]',
            limit: 5000,
            mimetype: 'application/font-woff'
          }
        }
      },
      {
        test: /\.(ttf|eot|svg|png)$/,
        use: {
          loader: 'file-loader',
          options: {
            name: 'fonts/[hash].[ext]'
          }
        }
      }
    ]
  },
  output: {
    path: resolve('dist/assets')
  },
  plugins: [
    new HtmlWebPackPlugin({
      template: "./src/index.html",
      filename: "./index.html"
    })
  ]
};
